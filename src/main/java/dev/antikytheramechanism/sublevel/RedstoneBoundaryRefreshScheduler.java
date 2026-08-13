package dev.antikytheramechanism.sublevel;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>Generic Frame neighbour callbacks still use {@link #request(ServerLevel, BlockPos)} through a
 * scheduled block tick because their origin can be managed-mini lifecycle work. Re-entry of the same
 * exact parent position is also deferred as a final safety net.</p>
 */
public final class RedstoneBoundaryRefreshScheduler {
    private static final double SHAPE_EPSILON = 1.0E-7;

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
     * <p>If the write changes the 2x2 overlap mask seen by any adjacent Frame, defer the affected
     * Frame(s) one tick. Signal-strength changes whose geometry is unchanged are replayed immediately.
     * This preserves same-tick dust propagation without permitting a geometry-dependent receiver to
     * invalidate and immediately restore its own powering quadrant forever in one tick.</p>
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
                defer(level, adjacent.position());
            }
            return;
        }

        ParentRefreshKey key = new ParentRefreshKey(level, parentPosition.immutable());
        Set<ParentRefreshKey> active = ACTIVE_PARENT_REFRESHES.get();
        if (active.contains(key)) {
            for (AdjacentFrame adjacent : adjacentFrames) {
                defer(level, adjacent.position());
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
        request(level, framePosition);
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
                    || manager.getAssemblyAt(framePosition).isEmpty()
                    || !level.getChunkAt(framePosition)
                            .getBlockState(framePosition)
                            .is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            result.add(new AdjacentFrame(framePosition.immutable(), directionToFrame.getOpposite()));
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

    private record AdjacentFrame(BlockPos position, Direction outwardFace) {
    }
}
