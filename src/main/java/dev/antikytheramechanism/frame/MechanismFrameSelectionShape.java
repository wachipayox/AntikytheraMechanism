package dev.antikytheramechanism.frame;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client picking affordance for a Mechanism Frame.
 *
 * <p>The rendered/collision bars are exactly 2/16 of a block thick. Requiring the crosshair to land
 * inside that same razor-thin area makes a coplanar outer mini block steal the hit almost every time
 * near an edge. Selection alone gets an extra half model pixel toward the opening. The exterior
 * boundary is unchanged, so aiming through the central opening still reaches mini content and this
 * never changes movement/collision geometry.</p>
 */
public final class MechanismFrameSelectionShape {
    static final double SELECTION_BAR = 2.5;
    private static final int CONNECTION_MASK_COUNT = 1 << Direction.values().length;
    private static final VoxelShape[] SHAPES = buildShapes();

    private MechanismFrameSelectionShape() {
    }

    public static VoxelShape shape(BlockState state) {
        int mask = 0;
        for (Direction direction : Direction.values()) {
            if (MechanismFrameBlock.isConnected(state, direction)) {
                mask |= 1 << direction.ordinal();
            }
        }
        return SHAPES[mask];
    }

    private static VoxelShape[] buildShapes() {
        VoxelShape[] shapes = new VoxelShape[CONNECTION_MASK_COUNT];
        for (int mask = 0; mask < shapes.length; mask++) {
            shapes[mask] = buildShape(mask);
        }
        return shapes;
    }

    private static VoxelShape buildShape(int connectionMask) {
        VoxelShape result = Shapes.empty();
        double bar = SELECTION_BAR;

        for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(connectionMask, ySide) && !connected(connectionMask, zSide)) {
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - bar;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - bar;
                    result = Shapes.or(result, Block.box(0, y0, z0, 16, y0 + bar, z0 + bar));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(connectionMask, xSide) && !connected(connectionMask, zSide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - bar;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - bar;
                    result = Shapes.or(result, Block.box(x0, 0, z0, x0 + bar, 16, z0 + bar));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
                if (!connected(connectionMask, xSide) && !connected(connectionMask, ySide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - bar;
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - bar;
                    result = Shapes.or(result, Block.box(x0, y0, 0, x0 + bar, y0 + bar, 16));
                }
            }
        }
        return result;
    }

    private static boolean connected(int connectionMask, Direction direction) {
        return (connectionMask & (1 << direction.ordinal())) != 0;
    }
}
