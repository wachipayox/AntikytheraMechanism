package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Finishes non-player Frame removals after the outer world write has returned.
 *
 * <p>Commands such as {@code /setblock ... air} enter {@link MechanismFrameBlock#onRemove} from
 * inside Sable's own setBlock interception. Starting mini-content evacuation from that callback is a
 * nested world mutation against an unfinished outer write. Player and explosion removal do not have
 * this problem because they evacuate before vanilla replaces the Frame. Programmatic removals that
 * arrive without that preparation are therefore queued until the manager's normal post-level-tick
 * maintenance entry point.</p>
 */
public final class DeferredFrameRemovalLifecycle {
    private static final Map<ServerLevel, Set<BlockPos>> PENDING = new WeakHashMap<>();

    private DeferredFrameRemovalLifecycle() {
    }

    public static void defer(ServerLevel level, BlockPos framePosition) {
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, ignored -> new LinkedHashSet<>())
                    .add(framePosition.immutable());
        }
    }

    public static void process(ServerLevel level) {
        Set<BlockPos> pending;
        synchronized (PENDING) {
            pending = PENDING.remove(level);
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (BlockPos framePosition : pending) {
            if (manager.getAssemblyAt(framePosition).isEmpty()) {
                continue;
            }

            // A replacement Frame can already exist if two commands/writes happened in one tick. Its
            // onPlace temporarily sees the old index; finish the old evacuation first, then bind the
            // physically present replacement as a fresh Frame below.
            boolean replacementFrame = level.getBlockState(framePosition)
                    .is(ModRegistries.MECHANISM_FRAME.get());

            if (!manager.isFrameEvacuated(framePosition)
                    && !manager.evacuateFrame(
                            level,
                            framePosition,
                            FrameEvacuationService.Cause.generic())) {
                AntikytheraMechanism.LOGGER.error(
                        "Deferred cleanup could not evacuate removed Mechanism Frame {}; preserving its assembly record for recovery",
                        framePosition);
                continue;
            }

            manager.onFrameRemoved(level, framePosition);
            if (replacementFrame
                    && level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())) {
                manager.onFramePlaced(level, framePosition);
            }
        }
    }
}
