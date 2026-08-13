package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;

/**
 * Reconciles macro -> mini redstone boundaries with vanilla-like same-tick latency while keeping
 * self-referential boundary geometry from recursively oscillating forever in one server tick.
 *
 * <p>The first request for a Frame runs synchronously. If that reconciliation changes a macro block
 * which immediately requests the same Frame again before the first pass has unwound, only that
 * re-entrant request is deferred through Minecraft's block scheduler. A contradictory setup such as
 * a trapdoor moving into/out of the mini quadrant that powers it therefore becomes a bounded one-tick
 * clock, while ordinary lever/dust propagation remains in the current tick.</p>
 */
public final class RedstoneBoundaryRefreshScheduler {
    private static final ThreadLocal<Set<FrameRefreshKey>> ACTIVE_REFRESHES =
            ThreadLocal.withInitial(HashSet::new);

    private RedstoneBoundaryRefreshScheduler() {
    }

    /** Requests a boundary refresh, running it now unless this same Frame is already refreshing. */
    public static void request(ServerLevel level, BlockPos framePosition) {
        if (!isFrame(level, framePosition)) {
            return;
        }

        FrameRefreshKey key = new FrameRefreshKey(level, framePosition.immutable());
        Set<FrameRefreshKey> active = ACTIVE_REFRESHES.get();
        if (active.contains(key)) {
            defer(level, framePosition);
            return;
        }

        runNow(level, framePosition, key, active);
    }

    /** Entry point for the fallback scheduled tick created by a re-entrant request. */
    public static void runScheduled(ServerLevel level, BlockPos framePosition) {
        request(level, framePosition);
    }

    private static void runNow(
            ServerLevel level,
            BlockPos framePosition,
            FrameRefreshKey key,
            Set<FrameRefreshKey> active) {
        active.add(key);
        try {
            RedstoneBoundaryBridge.refreshMiniBoundaryFromFrameNeighbor(level, framePosition);
            // Keep the macro notification inside the same re-entry scope. A shape-changing receiver
            // may write its own state from this call; that write must see this Frame as active and
            // defer rather than recursively starting another complete boundary pass.
            level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                ACTIVE_REFRESHES.remove();
            }
        }
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
}
