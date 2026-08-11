package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;

public final class MiniCoordinateMapper {
    public static final int CELLS_PER_FRAME_AXIS = 2;
    public static final double SUBLEVEL_SCALE = 0.5;

    private MiniCoordinateMapper() {
    }

    public static BlockPos frameToMini(MechanismAssembly assembly, BlockPos framePos, int x, int y, int z) {
        requireCellCoordinate(x);
        requireCellCoordinate(y);
        requireCellCoordinate(z);

        BlockPos frameOffset = framePos.subtract(assembly.origin());
        return new BlockPos(
                frameOffset.getX() * CELLS_PER_FRAME_AXIS + x,
                frameOffset.getY() * CELLS_PER_FRAME_AXIS + y,
                frameOffset.getZ() * CELLS_PER_FRAME_AXIS + z);
    }

    public static BlockPos miniToFrame(MechanismAssembly assembly, BlockPos miniPos) {
        return assembly.origin().offset(
                Math.floorDiv(miniPos.getX(), CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getY(), CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getZ(), CELLS_PER_FRAME_AXIS));
    }

    public static boolean isOwnedMiniPosition(MechanismAssembly assembly, BlockPos miniPos) {
        return assembly.frameMask().containsMini(miniPos);
    }

    public static BlockPos cellInFrame(BlockPos miniPos) {
        return new BlockPos(
                Math.floorMod(miniPos.getX(), CELLS_PER_FRAME_AXIS),
                Math.floorMod(miniPos.getY(), CELLS_PER_FRAME_AXIS),
                Math.floorMod(miniPos.getZ(), CELLS_PER_FRAME_AXIS));
    }

    public static int cellIndex(int x, int y, int z) {
        requireCellCoordinate(x);
        requireCellCoordinate(y);
        requireCellCoordinate(z);
        return x | y << 1 | z << 2;
    }

    public static boolean isCellOccupied(int mask, int x, int y, int z) {
        return (mask & 1 << cellIndex(x, y, z)) != 0;
    }

    private static void requireCellCoordinate(int coordinate) {
        if (coordinate < 0 || coordinate >= CELLS_PER_FRAME_AXIS) {
            throw new IllegalArgumentException("Mini cell coordinate must be 0 or 1: " + coordinate);
        }
    }
}
