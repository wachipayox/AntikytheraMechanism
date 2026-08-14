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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

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
    private static final ThreadLocal<Set<BlockPos>> SUPPRESSED_DIRECT_FRAME_OUTPUTS = new ThreadLocal<>();

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
                : projectedWeakSignal(
                        projectedState,
                        level,
                        boundary.parentPosition(),
                        direction,
                        boundary.framePosition());
    }

    /** Returns the signal a physical Mechanism Frame emits toward the querying macro-world side. */
    public static int frameOutputSignal(
            BlockGetter level,
            BlockPos framePosition,
            Direction queryDirection,
            boolean direct) {
        if (direct && directFrameOutputSuppressed(framePosition)) {
            return 0;
        }
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
            level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
        }
    }

    public static void refreshMiniBoundaryFromFrameNeighbor(ServerLevel level, BlockPos framePosition) {
        if (FRAME_REFRESH_DEPTH.get() > 0) {
            return;
        }
        FrameContext context = boundaryRefreshContext(level, framePosition);
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

    public static boolean refreshMiniBoundaryFromParentNeighbor(
            ServerLevel level,
            BlockPos framePosition,
            BlockPos parentPosition) {
        Direction boundary = directionFromTo(framePosition, parentPosition);
        if (boundary == null || FRAME_REFRESH_DEPTH.get() > 0) {
            return false;
        }
        FrameContext context = boundaryRefreshContext(level, framePosition);
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
                MiniCoordinateMapper.miniToFrame(assembly, shellMini),
                MiniCoordinateMapper.miniToFrame(assembly, interiorMini),
                a,
                b);
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

    /**
     * Lifecycle/topology refreshes must keep working after a Frame has been yaw-rotated by Create.
     * A rotated assembly is no longer identity-oriented, but it is still fully docked to the parent
     * world once disassembly commits. Redstone query paths keep their legacy identity-only fallback;
     * generic neighbour propagation uses the assembly's real docked orientation instead.
     */
    private static @Nullable FrameContext boundaryRefreshContext(ServerLevel level, BlockPos framePosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || !boundaryAvailable(manager, assembly)
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, WORLD_ALIGNED_EPSILON)) {
            return null;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        return subLevel == null || subLevel.isRemoved() ? null : new FrameContext(assembly, subLevel);
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

    /**
     * Create contraptions are different from piston/recovery transitions: their journal owns a
     * read-only local snapshot of the exact macro blocks travelling beside the Frame. That snapshot
     * remains a valid mini->macro input source while the physical world copies are absent, so do not
     * suppress redstone merely because a Create move is pending.
     */
    private static boolean boundaryAvailable(
            MechanismAssemblyManager manager, MechanismAssembly assembly) {
        return !manager.isContentRecoveryLocked(assembly.id())
                && manager.pendingPistonMove(assembly.id()).isEmpty();
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

    /**
     * Evaluates a projected macro block exactly like SignalGetter#getSignal, except that when the
     * macro block is a conductor we must not let the same Frame being queried count as one of that
     * conductor's direct inputs. Without this one-edge exclusion, lower mini cells can form the
     * artificial cycle mini -> Frame -> supporting macro conductor -> mini and remain powered after
     * the real macro source is removed. Other direct macro sources around the conductor still count.
     */
    static int projectedWeakSignal(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            Direction direction,
            BlockPos framePosition) {
        int signal = state.getSignal(level, position, direction);
        if (!state.shouldCheckWeakPower(level, position, direction)) {
            return signal;
        }
        int directSignal = withDirectFrameOutputSuppressed(
                framePosition,
                () -> level.getDirectSignalTo(position));
        return Math.max(signal, directSignal);
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

    private static int withDirectFrameOutputSuppressed(BlockPos framePosition, IntSupplier action) {
        Set<BlockPos> suppressed = SUPPRESSED_DIRECT_FRAME_OUTPUTS.get();
        boolean installed = suppressed == null;
        if (installed) {
            suppressed = new HashSet<>();
            SUPPRESSED_DIRECT_FRAME_OUTPUTS.set(suppressed);
        }

        BlockPos key = framePosition.immutable();
        boolean added = suppressed.add(key);
        try {
            return action.getAsInt();
        } finally {
            if (added) {
                suppressed.remove(key);
            }
            if (installed || suppressed.isEmpty()) {
                SUPPRESSED_DIRECT_FRAME_OUTPUTS.remove();
            }
        }
    }

    private static boolean directFrameOutputSuppressed(BlockPos framePosition) {
        Set<BlockPos> suppressed = SUPPRESSED_DIRECT_FRAME_OUTPUTS.get();
        return suppressed != null && suppressed.contains(framePosition);
    }

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
        return maxA > minB + SHAPE_EPSILON && maxB > minA + SHAPE_EPSILON;
    }

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePosition,
            Direction boundary,
            int a,
            int b) {
        Direction logical = assembly.orientation().toLogical(boundary);
        int x = a;
        int y = b;
        int z = 0;
        switch (logical.getAxis()) {
            case X -> {
                x = logical == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logical == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logical == Direction.NORTH ? 0 : 1;
            }
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
    }

    private record ProjectedBoundary(BlockPos parentPosition, BlockPos framePosition, int a, int b) {
    }

    private record FrameContext(MechanismAssembly assembly, ServerSubLevel subLevel) {
    }
}
