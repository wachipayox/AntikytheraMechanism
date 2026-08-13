package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.RedStoneWireBlockAccessor;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

public final class OrientedRedstoneBoundary {
    private static final double ALIGN = 1e-5;
    private OrientedRedstoneBoundary() {}

    public static @Nullable Integer projected(ServerLevel level, BlockPos global, Direction logicalDirection, boolean direct) {
        Context context = childContext(level, global);
        if (context == null || context.assembly.orientation().equals(FrameOrientation.IDENTITY)) return null;
        BlockState state = MiniWorldEnvironment.virtualBlockState(level, global);
        if (state == null) return 0;
        BlockPos shell = global.subtract(context.child.getPlot().getCenterBlock());
        BlockPos inside = shell.relative(logicalDirection.getOpposite());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(context.assembly, inside)) return null;
        BlockPos logicalCell = MiniCoordinateMapper.cellInFrame(inside);
        BlockPos physicalCell = context.assembly.orientation().logicalCellToPhysical(
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        Direction physicalDirection = context.assembly.orientation().toPhysical(logicalDirection);
        int[] ab = tangents(physicalDirection, physicalCell);
        BlockPos parent = MiniCoordinateMapper.miniToFrame(context.assembly, shell);
        if (!overlaps(state, level, parent, physicalDirection, ab[0], ab[1])) return 0;
        if (!direct && state.is(Blocks.REDSTONE_WIRE)) {
            if (!wireEnabled()) return 0;
            return physicalDirection == Direction.DOWN ? 0 : state.getValue(RedStoneWireBlock.POWER);
        }
        return direct ? state.getDirectSignal(level, parent, physicalDirection)
                : weak(state, level, parent, physicalDirection);
    }

    public static @Nullable Integer output(BlockGetter getter, BlockPos frame, Direction physicalQuery, boolean direct) {
        if (!(getter instanceof ServerLevel level)) return null;
        MechanismAssembly assembly = assemblyContext(level, frame);
        if (assembly == null || assembly.orientation().equals(FrameOrientation.IDENTITY)) return null;
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null) return 0;
        Direction outward = physicalQuery.getOpposite();
        BlockPos receiver = frame.relative(outward);
        if (assembly.containsFrame(receiver)) return 0;
        BlockState receiverState = level.getBlockState(receiver);
        Direction logicalQuery = assembly.orientation().toLogical(physicalQuery);
        int strongest = 0;
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            if (!overlaps(receiverState, level, receiver, outward, a, b)) continue;
            BlockPos pc = faceCell(outward, a, b);
            BlockPos lc = assembly.orientation().physicalCellToLogical(pc.getX(), pc.getY(), pc.getZ());
            BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, lc.getX(), lc.getY(), lc.getZ());
            BlockPos mini = MechanismSubLevelService.toPlotPosition(child, local);
            if (!level.hasChunkAt(mini)) continue;
            BlockState miniState = level.getBlockState(mini);
            if (miniState.isAir()) continue;
            int signal = MiniWorldEnvironment.withVirtualReads(() -> {
                if (!direct && miniState.is(Blocks.REDSTONE_WIRE)) {
                    if (!wireEnabled()) return 0;
                    return logicalQuery == Direction.DOWN ? 0 : miniState.getValue(RedStoneWireBlock.POWER);
                }
                return direct ? miniState.getDirectSignal(level, mini, logicalQuery)
                        : weak(miniState, level, mini, logicalQuery);
            });
            strongest = Math.max(strongest, signal);
        }
        return strongest;
    }

    public static @Nullable Boolean connects(BlockGetter getter, BlockPos frame, @Nullable Direction physicalDirection) {
        if (physicalDirection == null || !(getter instanceof ServerLevel level)) return null;
        MechanismAssembly assembly = assemblyContext(level, frame);
        if (assembly == null || assembly.orientation().equals(FrameOrientation.IDENTITY)) return null;
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null) return false;
        Direction outward = physicalDirection.getOpposite();
        BlockPos receiver = frame.relative(outward);
        if (assembly.containsFrame(receiver)) return false;
        BlockState receiverState = level.getBlockState(receiver);
        Direction logicalDirection = assembly.orientation().toLogical(physicalDirection);
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            if (!overlaps(receiverState, level, receiver, outward, a, b)) continue;
            BlockPos pc = faceCell(outward, a, b);
            BlockPos lc = assembly.orientation().physicalCellToLogical(pc.getX(), pc.getY(), pc.getZ());
            BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, lc.getX(), lc.getY(), lc.getZ());
            BlockPos mini = MechanismSubLevelService.toPlotPosition(child, local);
            if (level.hasChunkAt(mini) && !level.getBlockState(mini).isAir()
                    && MiniWorldEnvironment.withVirtualReads(() -> level.getBlockState(mini)
                    .canRedstoneConnectTo(level, mini, logicalDirection))) return true;
        }
        return false;
    }

    private static Context childContext(ServerLevel level, BlockPos global) {
        SubLevel containing = Sable.HELPER.getContaining(level, global);
        if (!(containing instanceof ServerSubLevel child)) return null;
        java.util.UUID owner = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (owner == null) return null;
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(owner).orElse(null);
        return assembly != null && available(level, assembly) ? new Context(assembly, child) : null;
    }

    private static MechanismAssembly assemblyContext(ServerLevel level, BlockPos frame) {
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(frame).orElse(null);
        return assembly != null && available(level, assembly) ? assembly : null;
    }

    private static boolean available(ServerLevel level, MechanismAssembly assembly) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        return !manager.isContentRecoveryLocked(assembly.id())
                && manager.pendingPistonMove(assembly.id()).isEmpty()
                && manager.pendingContraptionMove(assembly.id()).isEmpty()
                && MechanismAssemblyHost.boundaryIsAligned(level, assembly, ALIGN);
    }

    private static BlockPos faceCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static int[] tangents(Direction face, BlockPos cell) {
        return switch (face.getAxis()) {
            case X -> new int[]{cell.getY(), cell.getZ()};
            case Y -> new int[]{cell.getX(), cell.getZ()};
            case Z -> new int[]{cell.getX(), cell.getY()};
        };
    }

    private static boolean overlaps(BlockState state, BlockGetter level, BlockPos pos, Direction face, int a, int b) {
        if (state.isAir()) return false;
        var shape = state.getShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return true;
        double u0 = a * .5, u1 = u0 + .5, v0 = b * .5, v1 = v0 + .5;
        for (AABB box : shape.toAabbs()) {
            double minU, maxU, minV, maxV;
            switch (face.getAxis()) {
                case X -> { minU = box.minY; maxU = box.maxY; minV = box.minZ; maxV = box.maxZ; }
                case Y -> { minU = box.minX; maxU = box.maxX; minV = box.minZ; maxV = box.maxZ; }
                case Z -> { minU = box.minX; maxU = box.maxX; minV = box.minY; maxV = box.maxY; }
                default -> throw new IllegalStateException();
            }
            if (Math.min(maxU, u1) > Math.max(minU, u0) + 1e-7
                    && Math.min(maxV, v1) > Math.max(minV, v0) + 1e-7) return true;
        }
        return false;
    }

    private static int weak(BlockState state, ServerLevel level, BlockPos pos, Direction direction) {
        int signal = state.getSignal(level, pos, direction);
        return state.shouldCheckWeakPower(level, pos, direction) ? Math.max(signal, level.getDirectSignalTo(pos)) : signal;
    }

    private static boolean wireEnabled() {
        return ((RedStoneWireBlockAccessor) (Object) Blocks.REDSTONE_WIRE).antikytheramechanism$shouldSignal();
    }

    private record Context(MechanismAssembly assembly, ServerSubLevel child) {}
}
