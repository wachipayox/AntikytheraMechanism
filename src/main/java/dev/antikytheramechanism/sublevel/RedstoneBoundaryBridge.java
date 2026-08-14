package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.RedStoneWireBlockAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Read-only redstone bridge between the macro world and the managed 2x mini grid.
 *
 * <p>The boundary is treated as four independent 0.5x0.5 channels per exterior Frame face instead
 * of one magic full-block connection. A parent-world block only reaches the mini cells overlapped by
 * its actual outline shape on the shared face. The reverse direction uses the same rule against the
 * macro receiver. This keeps slabs, floor-level wire and other partial blocks spatially coherent.</p>
 *
 * <p>A face shared by two Frames in the same {@link MechanismAssembly} is deliberately not a
 * boundary at all: the mini grid is continuous across it and vanilla mini neighbour/redstone logic
 * owns that connection directly. No projected shell block becomes mutable. The bridge transports
 * signal/connectivity queries and vanilla neighbour notifications only; lifecycle, writes,
 * destruction and drops remain blocked by the read-only shell guards.</p>
 */
public final class RedstoneBoundaryBridge {
    private static final double WORLD_ALIGNED_EPSILON = 1.0E-5;
    private static final double SHAPE_EPSILON = 1.0E-7;
    private static final ThreadLocal<Integer> FRAME_REFRESH_DEPTH = ThreadLocal.withInitial(() -> 0);

    private RedstoneBoundaryBridge() {
    }

    /**
     * Returns the projected parent-world signal for a shell coordinate, or {@code null} when this
     * is not a managed virtual-shell query and vanilla should continue normally.
     */
    public static @Nullable Integer projectedParentSignal(
            ServerLevel level,
            BlockPos globalPlotPosition,
            Direction direction,
            boolean direct) {
        if (projectedBoundarySuppressed(level, globalPlotPosition)) return 0;
        Integer oriented = OrientedRedstoneBoundary.projected(level, globalPlotPosition, direction, direct);
        if (oriented != null) return oriented;
        BlockState projectedState = MiniWorldEnvironment.virtualBlockState(level, globalPlotPosition);
        if (projectedState == null) {
            return null;
        }

        ProjectedBoundary boundary = resolveProjectedBoundary(level, globalPlotPosition, direction);
        if (boundary == null) {
            return null;
        }
        if (!macroShapeOverlapsCell(
                projectedState,
                level,
                boundary.parentPosition(),
                direction,
                boundary.a(),
                boundary.b())) {
            return 0;
        }

        // Vanilla wire only emits horizontally toward directions represented by its connection
        // state. The Frame is not a real block inside that wire graph, so using the macro wire's
        // connection state here makes direct mini consumers (pistons, lamps, modded receivers)
        // disagree with diodes, which explicitly read RedStoneWireBlock.POWER. Across this explicit
        // boundary channel, use the wire's actual power value while preserving vanilla's no-DOWN
        // getSignal rule. Crucially, still respect RedStoneWireBlock.shouldSignal: vanilla disables
        // wire emission while recalculating a wire precisely so its current POWER cannot feed back
        // into its own target-strength query. Ignoring that guard creates a synthetic
        // mini -> Frame -> macro dust -> mini latch.
        if (!direct && projectedState.is(Blocks.REDSTONE_WIRE)) {
            if (!vanillaWireSignalsEnabled()) {
                return 0;
            }
            return direction == Direction.DOWN ? 0 : projectedState.getValue(RedStoneWireBlock.POWER);
        }

        return direct
                ? projectedState.getDirectSignal(level, boundary.parentPosition(), direction)
                : weakSignal(projectedState, level, boundary.parentPosition(), direction);
    }

    /** Returns the signal a physical Mechanism Frame emits toward the querying macro-world side. */
    public static int frameOutputSignal(
            BlockGetter level,
            BlockPos framePosition,
            Direction queryDirection,
            boolean direct) {
        Integer oriented = OrientedRedstoneBoundary.output(level, framePosition, queryDirection, direct);
        if (oriented != null) return oriented;
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        FrameContext context = frameContext(serverLevel, framePosition);
        if (context == null) {
            return 0;
        }

        Direction outwardFace = queryDirection.getOpposite();
        BlockPos receiverPosition = framePosition.relative(outwardFace);
        if (isInternalAssemblyFace(context.assembly(), receiverPosition)) {
            return 0;
        }
        BlockState receiverState = serverLevel.getBlockState(receiverPosition);

        int strongest = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (!macroShapeOverlapsCell(
                        receiverState, serverLevel, receiverPosition, outwardFace, a, b)) {
                    continue;
                }

                BlockPos global = boundaryGlobal(
                        context.assembly(), context.subLevel(), framePosition, outwardFace, a, b);
                if (!serverLevel.hasChunkAt(global)) {
                    continue;
                }
                BlockState miniState = serverLevel.getChunkAt(global).getBlockState(global);
                if (miniState.isAir()) {
                    continue;
                }

                int signal = MiniWorldEnvironment.withVirtualReads(() -> {
                    // Mirror the macro->mini wire rule. A mini dust line cannot literally include
                    // the physical Frame in its vanilla connection graph, so asking the dust state
                    // whether it is connected to that synthetic shell can report zero even though
                    // the explicit boundary channel is connected. Its POWER is the authoritative
                    // transported value once geometry has selected this face channel. As above,
                    // preserve vanilla's temporary shouldSignal=false guard during wire strength
                    // recalculation so a macro wire cannot keep itself alive through mini dust.
                    if (!direct && miniState.is(Blocks.REDSTONE_WIRE)) {
                        if (!vanillaWireSignalsEnabled()) {
                            return 0;
                        }
                        return queryDirection == Direction.DOWN
                                ? 0
                                : miniState.getValue(RedStoneWireBlock.POWER);
                    }
                    return direct
                            ? miniState.getDirectSignal(serverLevel, global, queryDirection)
                            : weakSignal(miniState, serverLevel, global, queryDirection);
                });
                strongest = Math.max(strongest, signal);
                if (strongest >= 15) {
                    return 15;
                }
            }
        }
        return strongest;
    }

    /**
     * Controls whether macro redstone dust visually/logically connects to a Frame face.
     *
     * <p>NeoForge asks the neighbour block this question while computing wire connections. The
     * Frame only answers yes when an overlapped mini boundary cell contains a block that would
     * itself accept a redstone-dust connection from that direction.</p>
     */
    public static boolean frameCanConnectRedstone(
            BlockState frameState,
            BlockGetter level,
            BlockPos framePosition,
            @Nullable Direction direction) {
        if (direction == null || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        FrameContext context = frameContext(serverLevel, framePosition);
        if (context == null) {
            return false;
        }

        Direction outwardFace = direction.getOpposite();
        BlockPos wirePosition = framePosition.relative(outwardFace);
        if (isInternalAssemblyFace(context.assembly(), wirePosition)) {
            return false;
        }
        BlockState wireState = serverLevel.getBlockState(wirePosition);

        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (!macroShapeOverlapsCell(wireState, serverLevel, wirePosition, outwardFace, a, b)) {
                    continue;
                }

                BlockPos global = boundaryGlobal(
                        context.assembly(), context.subLevel(), framePosition, outwardFace, a, b);
                if (!serverLevel.hasChunkAt(global)) {
                    continue;
                }
                BlockState miniState = serverLevel.getChunkAt(global).getBlockState(global);
                if (miniState.isAir()) {
                    continue;
                }

                boolean connects = MiniWorldEnvironment.withVirtualReads(
                        () -> miniState.canRedstoneConnectTo(serverLevel, global, direction));
                if (connects) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A mini state write can change both the signal and the redstone-connectable shape exposed by
     * its owning Frame. Notify macro neighbours and replay neighbour-shape updates so dust can turn
     * toward/away from the Frame when mini boundary content is added or removed.
     */
    public static void notifyParentForManagedWrite(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
            return;
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!level.hasChunkAt(framePosition)) {
            return;
        }
        BlockState frameState = level.getChunkAt(framePosition).getBlockState(framePosition);
        if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        frameState.updateNeighbourShapes(level, framePosition, Block.UPDATE_ALL);
        level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
    }

    /**
     * A parent BlockState may be placed while the Frame was already outputting power. Vanilla cannot
     * have notified that not-yet-existing receiver when the mini signal changed earlier, so replay a
     * Frame-originated neighbour update after the parent write. This is deliberately parent-only;
     * managed plot writes already use {@link #notifyParentForManagedWrite}.
     */
    public static void notifyFramesForParentWrite(ServerLevel level, BlockPos parentPosition) {
        if (Sable.HELPER.getContaining(level, parentPosition) != null) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = parentPosition.relative(directionToFrame);
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null || !level.hasChunkAt(framePosition)) {
                continue;
            }
            BlockState frameState = level.getChunkAt(framePosition).getBlockState(framePosition);
            if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())
                    || isInternalAssemblyFace(assembly, parentPosition)) {
                continue;
            }

            // updateNeighborsAt(frame) includes parentPosition and makes lamps, pistons, dust and
            // modded receivers evaluate the signal that was already present before their placement.
            // Sibling Frames are excluded because their shared face is continuous mini space.
            level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
        }
    }

    /**
     * Called when the physical Frame receives a generic macro-world neighbour update and the exact
     * source was unavailable (for example, after loading a persisted scheduled block tick).
     *
     * <p>Only exterior faces are replayed. A connected sibling Frame is not a virtual parent block;
     * mini blocks on both sides already neighbour one another directly inside the same SubLevel.</p>
     */
    public static void refreshMiniBoundaryFromFrameNeighbor(ServerLevel level, BlockPos framePosition) {
        if (FRAME_REFRESH_DEPTH.get() > 0) {
            return;
        }
        FrameContext context = frameContext(level, framePosition);
        if (context == null) {
            return;
        }

        int previous = FRAME_REFRESH_DEPTH.get();
        FRAME_REFRESH_DEPTH.set(previous + 1);
        try {
            for (Direction boundary : Direction.values()) {
                BlockPos parentPosition = framePosition.relative(boundary);
                if (isInternalAssemblyFace(context.assembly(), parentPosition)) {
                    continue;
                }
                MiniWorldEnvironment.parentBlockChanged(level, parentPosition);
            }
        } finally {
            if (previous == 0) {
                FRAME_REFRESH_DEPTH.remove();
            } else {
                FRAME_REFRESH_DEPTH.set(previous);
            }
        }
    }

    /**
     * Replays one exact deferred exterior boundary. Returns false when the source is no longer an
     * exterior face (notably because it is now a sibling Frame in the same Assembly), so the caller
     * can avoid emitting a synthetic macro neighbour notification for a no-op refresh.
     */
    public static boolean refreshMiniBoundaryFromParentNeighbor(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos parentPosition) {
        Direction boundary = directionFromTo(framePosition, parentPosition);
        if (boundary == null || FRAME_REFRESH_DEPTH.get() > 0) {
            return false;
        }
        FrameContext context = frameContext(level, framePosition);
        if (context == null || isInternalAssemblyFace(context.assembly(), parentPosition)) {
            return false;
        }

        int previous = FRAME_REFRESH_DEPTH.get();
        FRAME_REFRESH_DEPTH.set(previous + 1);
        try {
            MiniWorldEnvironment.parentBlockChanged(level, parentPosition);
            return true;
        } finally {
            if (previous == 0) {
                FRAME_REFRESH_DEPTH.remove();
            } else {
                FRAME_REFRESH_DEPTH.set(previous);
            }
        }
    }

    private static @Nullable ProjectedBoundary resolveProjectedBoundary(
            ServerLevel level,
            BlockPos globalPlotPosition,
            Direction outwardDirection) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return null;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        if (assembly == null || !boundaryAvailable(manager, assembly)) {
            return null;
        }

        BlockPos shellMini = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        BlockPos interiorMini = shellMini.relative(outwardDirection.getOpposite());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, interiorMini)) {
            return null;
        }

        BlockPos cell = MiniCoordinateMapper.cellInFrame(interiorMini);
        int a;
        int b;
        switch (outwardDirection.getAxis()) {
            case X -> {
                a = cell.getY();
                b = cell.getZ();
            }
            case Y -> {
                a = cell.getX();
                b = cell.getZ();
            }
            case Z -> {
                a = cell.getX();
                b = cell.getY();
            }
            default -> throw new IllegalStateException("Unexpected axis " + outwardDirection.getAxis());
        }

        return new ProjectedBoundary(
                MiniCoordinateMapper.miniToFrame(assembly, shellMini), a, b);
    }

    private static @Nullable FrameContext frameContext(ServerLevel level, BlockPos framePosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || !boundaryAvailable(manager, assembly)
                || !assembly.poseTarget().approximatelyEquals(
                        AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
            return null;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        return subLevel == null ? null : new FrameContext(assembly, subLevel);
    }

    private static boolean projectedBoundarySuppressed(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return false;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) return false;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        return assembly != null && !boundaryAvailable(manager, assembly);
    }

    private static boolean boundaryAvailable(
            MechanismAssemblyManager manager, MechanismAssembly assembly) {
        return !manager.isContentRecoveryLocked(assembly.id())
                && manager.pendingPistonMove(assembly.id()).isEmpty()
                && manager.pendingContraptionMove(assembly.id()).isEmpty();
    }

    private static boolean isInternalAssemblyFace(
            MechanismAssembly assembly,
            BlockPos parentPosition) {
        return assembly.containsFrame(parentPosition);
    }

    private static @Nullable Direction directionFromTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return null;
        }
        return Direction.fromDelta(dx, dy, dz);
    }

    private static BlockPos boundaryGlobal(
            MechanismAssembly assembly,
            ServerSubLevel subLevel,
            BlockPos framePosition,
            Direction boundary,
            int a,
            int b) {
        BlockPos local = boundaryCell(assembly, framePosition, boundary, a, b);
        return MechanismSubLevelService.toPlotPosition(subLevel, local);
    }

    private static boolean vanillaWireSignalsEnabled() {
        return ((RedStoneWireBlockAccessor) (Object) Blocks.REDSTONE_WIRE)
                .antikytheramechanism$shouldSignal();
    }

    private static int weakSignal(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            Direction direction) {
        int signal = state.getSignal(level, position, direction);
        return state.shouldCheckWeakPower(level, position, direction)
                ? Math.max(signal, level.getDirectSignalTo(position))
                : signal;
    }

    /**
     * Tests the projection of a macro block's outline shape against one of the four 0.5x0.5 face
     * channels. The normal-axis thickness is intentionally ignored: adjacency already establishes
     * the shared face, while the two tangential axes decide which mini cells are physically aligned.
     */
    private static boolean macroShapeOverlapsCell(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            Direction face,
            int a,
            int b) {
        if (state.isAir()) {
            return false;
        }

        VoxelShape shape = state.getShape(level, position, CollisionContext.empty());
        if (shape.isEmpty()) {
            // Compatibility fallback for signal-capable/modded blocks with no outline geometry.
            return true;
        }

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

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePosition,
            Direction boundary,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (boundary.getAxis()) {
            case X -> {
                x = boundary == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = boundary == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = boundary == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + boundary.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
    }

    private record FrameContext(MechanismAssembly assembly, ServerSubLevel subLevel) {
    }

    private record ProjectedBoundary(BlockPos parentPosition, int a, int b) {
    }
}
