package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Reconciles macro -> mini redstone boundaries without turning every parent BlockState write into a
 * full six-face Frame replay.
 *
 * <p>Signal-only parent changes keep vanilla-like same-tick propagation. Changes that alter which of
 * the four 0.5x0.5 boundary cells are physically overlapped are deferred one tick instead. That
 * distinction is important for self-referential geometry: a trapdoor can be powered by a mini cell
 * which stops overlapping the trapdoor when it opens. Replaying that topology change immediately
 * lets CLOSED -> OPEN -> CLOSED recurse forever in one server tick, while deferring the geometry
 * transition turns it into a bounded clock. Ordinary dust POWER changes keep the same overlap mask
 * and therefore remain immediate.</p>
 *
 * <p>Deferred refreshes retain the exact parent position that caused them. A connected Frame face is
 * internal mini space, not a macro boundary, so Frame-to-Frame neighbour callbacks are discarded once
 * both positions belong to the same logical assembly. A scheduled tick whose source metadata was lost
 * across a save/reload falls back to the bounded full-exterior refresh.</p>
 */
public final class RedstoneBoundaryRefreshScheduler {
    private static final double SHAPE_EPSILON = 1.0E-7;

    private static final ThreadLocal<Set<FrameRefreshKey>> ACTIVE_FRAME_REFRESHES =
            ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Set<ParentRefreshKey>> ACTIVE_PARENT_REFRESHES =
            ThreadLocal.withInitial(HashSet::new);

    /**
     * Vanilla block ticks carry only a BlockPos and Block. Keep the exact boundary source beside the
     * scheduled tick so a deferred geometry/re-entry refresh does not degrade into six synthetic
     * neighbour callbacks. Weak level keys avoid retaining unloaded dimensions if a tick disappears.
     */
    private static final Map<ServerLevel, Map<BlockPos, PendingFrameRefresh>> PENDING_FRAME_REFRESHES =
            new WeakHashMap<>();

    private RedstoneBoundaryRefreshScheduler() {
    }

    /** Requests a full exterior Frame refresh, running it now unless this same Frame is refreshing. */
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
     * Called by the physical Frame's generic neighbour callback. The callback already tells us the
     * exact source position, so preserve it instead of scheduling an anonymous full Frame refresh.
     */
    public static void requestFromNeighbor(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos sourcePosition) {
        if (!isFrame(level, framePosition)) {
            return;
        }
        if (isInternalAssemblyFrameNeighbor(level, framePosition, sourcePosition)) {
            return;
        }
        if (directionFromTo(framePosition, sourcePosition) == null) {
            // Defensive fallback for modded/indirect callbacks whose source is not one block away.
            defer(level, framePosition);
            return;
        }
        defer(level, framePosition, sourcePosition);
    }

    /**
     * Replays only the boundary represented by a concrete parent-world BlockState write.
     *
     * <p>If the write changes the 2x2 overlap mask seen by any adjacent Frame, defer the affected
     * exact boundary one tick. Signal-strength changes whose geometry is unchanged are replayed
     * immediately. This preserves same-tick dust propagation without permitting a geometry-dependent
     * receiver to invalidate and immediately restore its own powering quadrant forever in one tick.</p>
     */
    public static void requestParentWrite(
            ServerLevel level,
            BlockPos parentPosition,
            BlockState previousState,
            BlockState newState) {
        List<AdjacentFrame> adjacentFrames = adjacentFrames(level, parentPosition);
        if (adjacentFrames.isEmpty()) {
            return;
        }

        if (boundaryTopologyChanged(level, parentPosition, previousState, newState, adjacentFrames)) {
            for (AdjacentFrame adjacent : adjacentFrames) {
                defer(level, adjacent.position(), parentPosition);
            }
            return;
        }

        ParentRefreshKey key = new ParentRefreshKey(level, parentPosition.immutable());
        Set<ParentRefreshKey> active = ACTIVE_PARENT_REFRESHES.get();
        if (active.contains(key)) {
            for (AdjacentFrame adjacent : adjacentFrames) {
                defer(level, adjacent.position(), parentPosition);
            }
            return;
        }

        active.add(key);
        try {
            // MiniWorldEnvironment already resolves the exact shared face(s) for this parent block;
            // do not expand this into a full Frame scan.
            MiniWorldEnvironment.parentBlockChanged(level, parentPosition);

            // The mini callbacks above may change what a Frame emits toward other macro neighbours.
            // Let those receivers pull the resulting signal in this same tick. Sibling Frames ignore
            // this generic notification because their shared face is internal mini space.
            for (AdjacentFrame adjacent : adjacentFrames) {
                level.updateNeighborsAt(adjacent.position(), ModRegistries.MECHANISM_FRAME.get());
            }
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                ACTIVE_PARENT_REFRESHES.remove();
            }
        }
    }

    /** Entry point for generic/re-entrant/topology fallback scheduled ticks. */
    public static void runScheduled(ServerLevel level, BlockPos framePosition) {
        PendingFrameRefresh pending = takePending(level, framePosition);
        if (pending == null || pending.fullRefresh()) {
            // Scheduled block ticks persist independently from this in-memory source cache. If a world
            // reload loses the source metadata, preserve correctness with one full exterior replay.
            request(level, framePosition);
            return;
        }
        requestBoundaries(level, framePosition, pending.parentPositions());
    }

    private static void requestBoundaries(
            ServerLevel level,
            BlockPos framePosition,
            Set<BlockPos> parentPositions) {
        if (!isFrame(level, framePosition) || parentPositions.isEmpty()) {
            return;
        }

        FrameRefreshKey key = new FrameRefreshKey(level, framePosition.immutable());
        Set<FrameRefreshKey> active = ACTIVE_FRAME_REFRESHES.get();
        if (active.contains(key)) {
            for (BlockPos parentPosition : parentPositions) {
                defer(level, framePosition, parentPosition);
            }
            return;
        }

        active.add(key);
        try {
            boolean refreshed = false;
            for (BlockPos parentPosition : parentPositions) {
                refreshed |= RedstoneBoundaryBridge.refreshMiniBoundaryFromParentNeighbor(
                        level, framePosition, parentPosition);
            }
            if (refreshed) {
                level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
            }
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                ACTIVE_FRAME_REFRESHES.remove();
            }
        }
    }

    private static boolean boundaryTopologyChanged(
            ServerLevel level,
            BlockPos parentPosition,
            BlockState previousState,
            BlockState newState,
            List<AdjacentFrame> adjacentFrames) {
        for (AdjacentFrame adjacent : adjacentFrames) {
            int before = boundaryCellMask(previousState, level, parentPosition, adjacent.outwardFace());
            int after = boundaryCellMask(newState, level, parentPosition, adjacent.outwardFace());
            if (before != after) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the same four-cell tangential occupancy model used by the redstone boundary bridge.
     * Normal-axis thickness is intentionally irrelevant: physical adjacency already establishes the
     * shared face, while these two axes decide which mini quadrants can participate.
     */
    private static int boundaryCellMask(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            Direction face) {
        if (state.isAir()) {
            return 0;
        }

        VoxelShape shape = state.getShape(level, position, CollisionContext.empty());
        if (shape.isEmpty()) {
            // Keep the bridge's compatibility fallback for signal-capable/modded blocks without an
            // outline shape: they are treated as overlapping the whole boundary face.
            return 0b1111;
        }

        int mask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (shapeOverlapsCell(shape, face, a, b)) {
                    mask |= 1 << (a * 2 + b);
                }
            }
        }
        return mask;
    }

    private static boolean shapeOverlapsCell(VoxelShape shape, Direction face, int a, int b) {
        double u0 = a * 0.5;
        double u1 = u0 + 0.5;
        double v0 = b * 0.5;
        double v1 = v0 + 0.5;

        for (AABB box : shape.toAabbs()) {
            double minU;
            double maxU;
            double minV;
            double maxV;
            switch (face.getAxis()) {
                case X -> {
                    minU = box.minY;
                    maxU = box.maxY;
                    minV = box.minZ;
                    maxV = box.maxZ;
                }
                case Y -> {
                    minU = box.minX;
                    maxU = box.maxX;
                    minV = box.minZ;
                    maxV = box.maxZ;
                }
                case Z -> {
                    minU = box.minX;
                    maxU = box.maxX;
                    minV = box.minY;
                    maxV = box.maxY;
                }
                default -> throw new IllegalStateException("Unexpected axis " + face.getAxis());
            }

            if (overlaps(minU, maxU, u0, u1) && overlaps(minV, maxV, v0, v1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB) > SHAPE_EPSILON;
    }

    private static List<AdjacentFrame> adjacentFrames(ServerLevel level, BlockPos parentPosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<AdjacentFrame> result = new ArrayList<>(2);
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = parentPosition.relative(directionToFrame);
            if (!level.hasChunkAt(framePosition)
                    || !level.getChunkAt(framePosition)
                            .getBlockState(framePosition)
                            .is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null) {
                continue;
            }

            // A currently-present sibling Frame is the continuation of the same mini grid. Do not
            // feed its BlockState changes back through the macro boundary system. If that Frame was
            // actually removed, the current parent state is no longer a Frame and the newly-exposed
            // exterior face is allowed through so mini neighbours see the topology change once.
            if (assembly.containsFrame(parentPosition)
                    && level.hasChunkAt(parentPosition)
                    && level.getChunkAt(parentPosition)
                            .getBlockState(parentPosition)
                            .is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            result.add(new AdjacentFrame(framePosition.immutable(), directionToFrame.getOpposite()));
        }
        return result;
    }

    private static boolean isInternalAssemblyFrameNeighbor(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos neighborPosition) {
        if (directionFromTo(framePosition, neighborPosition) == null
                || !level.hasChunkAt(neighborPosition)
                || !level.getChunkAt(neighborPosition)
                        .getBlockState(neighborPosition)
                        .is(ModRegistries.MECHANISM_FRAME.get())) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssemblyAt(framePosition)
                .orElse(null);
        return assembly != null && assembly.containsFrame(neighborPosition);
    }

    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return null;
        }
        return Direction.fromDelta(dx, dy, dz);
    }

    private static void defer(ServerLevel level, BlockPos framePosition) {
        rememberPending(level, framePosition, null);
        level.scheduleTick(framePosition, ModRegistries.MECHANISM_FRAME.get(), 1);
    }

    private static void defer(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos parentPosition) {
        rememberPending(level, framePosition, parentPosition);
        level.scheduleTick(framePosition, ModRegistries.MECHANISM_FRAME.get(), 1);
    }

    private static void rememberPending(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos parentPosition) {
        synchronized (PENDING_FRAME_REFRESHES) {
            Map<BlockPos, PendingFrameRefresh> byFrame = PENDING_FRAME_REFRESHES.computeIfAbsent(
                    level, ignored -> new HashMap<>());
            PendingFrameRefresh pending = byFrame.computeIfAbsent(
                    framePosition.immutable(), ignored -> new PendingFrameRefresh());
            if (parentPosition == null) {
                pending.requestFull();
            } else {
                pending.add(parentPosition.immutable());
            }
        }
    }

    private static PendingFrameRefresh takePending(ServerLevel level, BlockPos framePosition) {
        synchronized (PENDING_FRAME_REFRESHES) {
            Map<BlockPos, PendingFrameRefresh> byFrame = PENDING_FRAME_REFRESHES.get(level);
            if (byFrame == null) {
                return null;
            }
            PendingFrameRefresh pending = byFrame.remove(framePosition);
            if (byFrame.isEmpty()) {
                PENDING_FRAME_REFRESHES.remove(level);
            }
            return pending;
        }
    }

    private static boolean isFrame(ServerLevel level, BlockPos framePosition) {
        return level.hasChunkAt(framePosition)
                && level.getChunkAt(framePosition)
                        .getBlockState(framePosition)
                        .is(ModRegistries.MECHANISM_FRAME.get());
    }

    private static final class PendingFrameRefresh {
        private boolean fullRefresh;
        private final Set<BlockPos> parentPositions = new HashSet<>();

        void requestFull() {
            fullRefresh = true;
            parentPositions.clear();
        }

        void add(BlockPos parentPosition) {
            if (!fullRefresh) {
                parentPositions.add(parentPosition);
            }
        }

        boolean fullRefresh() {
            return fullRefresh;
        }

        Set<BlockPos> parentPositions() {
            return Set.copyOf(parentPositions);
        }
    }

    private record FrameRefreshKey(ServerLevel level, BlockPos position) {
    }

    private record ParentRefreshKey(ServerLevel level, BlockPos position) {
    }

    private record AdjacentFrame(BlockPos position, Direction outwardFace) {
    }
}
