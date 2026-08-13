package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
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

/** Non-identity counterpart of the vanilla-wire continuity exception at the Frame boundary. */
public final class OrientedRedstoneWireContinuity {
    private static final double EPSILON = 1.0E-7;
    private static final double ALIGN_EPSILON = 1.0E-5;

    private OrientedRedstoneWireContinuity() {}

    public static @Nullable Integer augment(
            BlockGetter getter,
            BlockPos framePosition,
            Direction queryDirection,
            int existingSignal) {
        if (!(getter instanceof ServerLevel level)) return null;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null || assembly.orientation().equals(FrameOrientation.IDENTITY)) return null;
        if (manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, ALIGN_EPSILON)) {
            return existingSignal;
        }

        // Mirrors vanilla wire's no-DOWN weak-power rule and only augments an actual macro wire.
        if (queryDirection == Direction.DOWN) return existingSignal;
        Direction physicalOutward = queryDirection.getOpposite();
        BlockPos receiverPosition = framePosition.relative(physicalOutward);
        if (assembly.containsFrame(receiverPosition)) return existingSignal;
        BlockState receiver = level.getBlockState(receiverPosition);
        if (!receiver.is(Blocks.REDSTONE_WIRE)) return existingSignal;

        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null || child.isRemoved()) return existingSignal;

        int strongest = existingSignal;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (!shapeOverlaps(receiver, level, receiverPosition, physicalOutward, a, b)) continue;
                BlockPos physicalCell = faceCell(physicalOutward, a, b);
                BlockPos logicalCell = assembly.orientation().physicalCellToLogical(
                        physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
                BlockPos local = MiniCoordinateMapper.frameToMini(
                        assembly,
                        framePosition,
                        logicalCell.getX(),
                        logicalCell.getY(),
                        logicalCell.getZ());
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                if (!level.hasChunkAt(global)) continue;
                BlockState mini = level.getBlockState(global);
                if (!mini.is(Blocks.REDSTONE_WIRE)) continue;
                strongest = Math.max(strongest,
                        Math.max(0, mini.getValue(RedStoneWireBlock.POWER) - 1));
            }
        }
        return strongest;
    }

    private static BlockPos faceCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static boolean shapeOverlaps(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            Direction face,
            int a,
            int b) {
        var shape = state.getShape(level, position, CollisionContext.empty());
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
            if (Math.min(maxU, u1) - Math.max(minU, u0) > EPSILON
                    && Math.min(maxV, v1) - Math.max(minV, v0) > EPSILON) {
                return true;
            }
        }
        return false;
    }
}
