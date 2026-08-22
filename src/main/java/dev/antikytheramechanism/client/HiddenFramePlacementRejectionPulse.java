package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-second red wireframe pulse used to reveal a hidden Frame after a rejected placement. */
public final class HiddenFramePlacementRejectionPulse {
    static final long DURATION_NANOS = 1_000_000_000L;
    private static final Map<UUID, Long> STARTED_AT = new ConcurrentHashMap<>();

    private HiddenFramePlacementRejectionPulse() {
    }

    public static void trigger(Level level, BlockPos position) {
        if (level == null || !level.isClientSide || position == null) {
            return;
        }

        // Direct Frame hits can resolve from the synchronized BE immediately.
        UUID assemblyId = hiddenAssemblyAtFrame(level, position);
        if (assemblyId == null) {
            // Rejected mini placements arrive with coordinates in the Level that produced the
            // interaction. That Level may itself be a foreign Sable SubLevel, so resolving from the
            // root ClientLevel (getContainingClient(position)) loses nested managed children. Use the
            // same Level-aware lookup as the mini-placement router; it is O(1) plot lookup and needs
            // no physical Frame scan or transform reconstruction.
            SubLevel containing = Sable.HELPER.getContaining(level, position);
            if (!(containing instanceof ClientSubLevel child)
                    || !ManagedClientSubLevelIdentity.isManaged(child)) {
                return;
            }
            assemblyId = ManagedClientSubLevelIdentity.assemblyId(child);
        }

        if (assemblyId != null) {
            STARTED_AT.put(assemblyId, System.nanoTime());
        }
    }

    /** Smooth 0 -> 1 -> 0 breath over one second. */
    public static float alpha(@Nullable UUID assemblyId) {
        if (assemblyId == null) {
            return 0.0f;
        }
        Long startedAt = STARTED_AT.get(assemblyId);
        if (startedAt == null) {
            return 0.0f;
        }
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed < 0L || elapsed >= DURATION_NANOS) {
            STARTED_AT.remove(assemblyId, startedAt);
            return 0.0f;
        }
        return pulseAlpha(elapsed);
    }

    static float pulseAlpha(long elapsedNanos) {
        if (elapsedNanos < 0L || elapsedNanos >= DURATION_NANOS) {
            return 0.0f;
        }
        return (float) Math.sin(Math.PI * (elapsedNanos / (double) DURATION_NANOS));
    }

    private static @Nullable UUID hiddenAssemblyAtFrame(Level level, BlockPos framePosition) {
        if (!level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())
                || level.getBlockState(framePosition).getValue(MechanismFrameBlock.SHELL_MODE) != FrameShellMode.HIDDEN
                || !(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frame)) {
            return null;
        }
        return frame.getAssemblyId();
    }
}
