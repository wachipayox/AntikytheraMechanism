package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciles macro -> mini redstone boundaries without turning every parent BlockState write into a
 * full six-face Frame replay.
 *
 * <p>When the exact parent position that changed is known, only that projected boundary is replayed
 * in the current tick. This mirrors vanilla's local neighbour propagation and is especially important
 * for redstone dust shutdowns, where many POWER states can change during one neighbour cascade. The
 * old path refreshed all six Frame faces for every one of those writes and multiplied a normal wire
 * decay into a large amount of mini lifecycle work.</p>
 *
 * <p>Generic Frame neighbour callbacks still use {@link #request(ServerLevel, BlockPos)} through a
 * scheduled block tick because their origin can be managed-mini lifecycle work. Re-entry of the same
 * exact parent position is also deferred; this preserves the trapdoor/shape-oscillation safety without
 * delaying ordinary propagation through different wire positions.</p>
 */
public final class RedstoneBoundaryRefreshScheduler {
    private static final ThreadLocal<Set<FrameRefreshKey>> ACTIVE_FRAME_REFRESHES =
            ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Set<ParentRefreshKey>> ACTIVE_PARENT_REFRESHES =
            ThreadLocal.withInitial(HashSet::new);

    private RedstoneBoundaryRefreshScheduler() {
    }

    /** Requests a full Frame refresh, running it now unless this same Frame is already refreshing. */
    public static void request(ServerLevel level, BlockPos framePosition) {
        if (!isFrame(level, framePosition)) {
            return;
        }

        FrameRefreshKey key = new FrameRefreshKey(level, framePosition.immutable());
        Set<FrameRefreshKey> active = ACTIVE_FRAME_REFRESHES.get();
        if (active.contains(key)) {
            defer(level, framePosition);
            return;
        }

        active.add(key);
        try {
            RedstoneBoundaryBridge.refreshMiniBoundaryFromFrameNeighbor(level, framePosition);
            level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                ACTIVE_FRAME_REFRESHES.remove();
            }
        }
    }

    /**
     * Replays only the boundary represented by a concrete parent-world BlockState write.
     *
     * <p>A repeated write to the same parent position while its replay is still active is a genuine
     * self-reference. Defer the adjacent Frames rather than recursively replaying that position. A
     * normal redstone line, however, changes successive parent positions and therefore remains a
     * same-tick propagation just like vanilla.</p>
     */
    public static void requestParentWrite(ServerLevel level, BlockPos parentPosition) {
        List<BlockPos> adjacentFrames = adjacentFrames(level, parentPosition);
        if (adjacentFrames.isEmpty()) {
            return;
        }

        ParentRefreshKey key = new ParentRefreshKey(level, parentPosition.immutable());
        Set<ParentRefreshKey> active = ACTIVE_PARENT_REFRESHES.get();
        if (active.contains(key)) {
            for (BlockPos framePosition : adjacentFrames) {
                defer(level, framePosition);
            }
            return;
        }

        active.add(key);
        try {
            // MiniWorldEnvironment already resolves the exact shared face(s) for this parent block;
            // do not expand this into refreshMiniBoundaryFromFrameNeighbor's six-face scan.
            MiniWorldEnvironment.parentBlockChanged(level, parentPosition);

            // The mini callbacks above may change what a Frame emits toward other macro neighbours.
            // Let those receivers pull the resulting signal in this same tick without replaying the
            // mini boundary a second time.
            for (BlockPos framePosition : adjacentFrames) {
                level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
            }
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                ACTIVE_PARENT_REFRESHES.remove();
            }
        }
    }

    /** Entry point for generic/re-entrant fallback scheduled ticks. */
    public static void runScheduled(ServerLevel level, BlockPos framePosition) {
        request(level, framePosition);
    }

    private static List<BlockPos> adjacentFrames(ServerLevel level, BlockPos parentPosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<BlockPos> result = new ArrayList<>(2);
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = parentPosition.relative(directionToFrame);
            if (!level.hasChunkAt(framePosition)
                    || manager.getAssemblyAt(framePosition).isEmpty()
                    || !level.getChunkAt(framePosition)
                            .getBlockState(framePosition)
                            .is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            result.add(framePosition.immutable());
        }
        return result;
    }

    private static void defer(ServerLevel level, BlockPos framePosition) {
        level.scheduleTick(framePosition, ModRegistries.MECHANISM_FRAME.get(), 1);
    }

    private static boolean isFrame(ServerLevel level, BlockPos framePosition) {
        return level.hasChunkAt(framePosition)
                && level.getChunkAt(framePosition)
                        .getBlockState(framePosition)
                        .is(ModRegistries.MECHANISM_FRAME.get());
    }

    private record FrameRefreshKey(ServerLevel level, BlockPos position) {
    }

    private record ParentRefreshKey(ServerLevel level, BlockPos position) {
    }
}
