package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Restores the one piece of vanilla wire semantics that a physical Frame cannot expose by itself.
 *
 * <p>A mini redstone wire and a macro redstone wire touching across a Frame boundary are logically
 * adjacent wire segments. During a macro wire strength recalculation vanilla temporarily disables
 * RedStoneWireBlock weak emission globally. That correctly prevents feedback from ordinary dust, but
 * it also makes {@link RedstoneBoundaryBridge#frameOutputSignal} hide the mini wire completely because
 * mini and macro dust share the same RedStoneWireBlock singleton.</p>
 *
 * <p>For wire-to-wire boundary connections only, read the mini wire's stored POWER as a neighbouring
 * wire and apply vanilla's one-step attenuation. This lets mini dust drive macro dust while ensuring
 * two stale dust values cannot hold each other at constant strength after the real source disappears.
 * Non-wire sources and receivers continue through RedstoneBoundaryBridge unchanged.</p>
 */
public final class RedstoneBoundaryWireContinuity {
    private static final double WORLD_ALIGNED_EPSILON = 1.0E-5;
    private static final double SHAPE_EPSILON = 1.0E-7;

    private RedstoneBoundaryWireContinuity() {
    }

    public static int augmentMacroWireSignal(
            BlockGetter level,
            BlockPos framePosition,
            Direction queryDirection,
            int bridgedSignal) {
        Integer oriented = OrientedRedstoneWireContinuity.augment(
                level, framePosition, queryDirection, bridgedSignal);
        if (oriented != null) return oriented;
        if (!(level instanceof ServerLevel serverLevel) || queryDirection == Direction.DOWN) {
            return bridgedSignal;
        }

        Direction outwardFace = queryDirection.getOpposite();
        BlockPos receiverPosition = framePosition.relative(outwardFace);
        BlockState receiverState = serverLevel.getBlockState(receiverPosition);
        if (!receiverState.is(Blocks.REDSTONE_WIRE)) {
            return bridgedSignal;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || !assembly.poseTarget().approximatelyEquals(
                        AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
            return bridgedSignal;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(serverLevel, assembly);
        if (subLevel == null) {
            return bridgedSignal;
        }

        int strongest = bridgedSignal;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (!macroShapeOverlapsCell(receiverState, serverLevel, receiverPosition, outwardFace, a, b)) {
                    continue;
                }

                BlockPos local = boundaryCell(assembly, framePosition, outwardFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, local);
                if (!serverLevel.hasChunkAt(global)) {
                    continue;
                }

                BlockState miniState = serverLevel.getChunkAt(global).getBlockState(global);
                if (!miniState.is(Blocks.REDSTONE_WIRE)) {
                    continue;
                }

                int neighbourWireSignal = Math.max(0, miniState.getValue(RedStoneWireBlock.POWER) - 1);
                strongest = Math.max(strongest, neighbourWireSignal);
            }
        }
        return strongest;
    }

    private static boolean macroShapeOverlapsCell(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            Direction face,
            int a,
            int b) {
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
}
