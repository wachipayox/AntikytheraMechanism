package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-second white wireframe pulse used to reveal a hidden Frame after a rejected placement. */
public final class HiddenFramePlacementRejectionPulse {
    static final long DURATION_NANOS = 1_000_000_000L;
    private static final String MANAGED_NAME_PREFIX = "antikythera-";
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
            // Rejected mini placements normally arrive here with a storage position in the managed
            // child plot. Do not require ManagedClientFrameHost.resolveOwningFrame(): that resolver is
            // intentionally strict about nested host transforms and may be temporarily unavailable
            // when the physical Frame itself lives in another Sable SubLevel. The managed child's
            // stable name already encodes exactly the same assembly UUID needed by the renderer.
            ClientSubLevel child = Sable.HELPER.getContainingClient(position);
            if (child == null || !ManagedClientSubLevelIdentity.isManaged(child)) {
                return;
            }
            assemblyId = managedAssemblyId(child);
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

    private static @Nullable UUID managedAssemblyId(ClientSubLevel child) {
        String name = child.getName();
        if (name == null || !name.startsWith(MANAGED_NAME_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(MANAGED_NAME_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
