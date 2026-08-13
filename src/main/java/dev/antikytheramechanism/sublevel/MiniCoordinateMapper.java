package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;

public final class MiniCoordinateMapper {
    public static final int CELLS_PER_FRAME_AXIS = 2;
    public static final double SUBLEVEL_SCALE = 0.5;

    private MiniCoordinateMapper() {}

    /** x/y/z are immutable logical cell coordinates. */
    public static BlockPos frameToMini(MechanismAssembly assembly, BlockPos framePos, int x, int y, int z) {
        requireCellCoordinate(x);
        requireCellCoordinate(y);
        requireCellCoordinate(z);
        BlockPos offset = assembly.logicalFrameOffset(framePos);
        return new BlockPos(
                offset.getX() * CELLS_PER_FRAME_AXIS + x,
                offset.getY() * CELLS_PER_FRAME_AXIS + y,
                offset.getZ() * CELLS_PER_FRAME_AXIS + z);
    }

    /** Converts a 0/1 cell selected in the physical Frame into the immutable logical plot. */
    public static BlockPos physicalFrameCellToMini(
            MechanismAssembly assembly, BlockPos framePos, int x, int y, int z) {
        requireCellCoordinate(x);
        requireCellCoordinate(y);
        requireCellCoordinate(z);
        BlockPos logical = assembly.orientation().physicalCellToLogical(x, y, z);
        return frameToMini(assembly, framePos, logical.getX(), logical.getY(), logical.getZ());
    }

    public static BlockPos miniToFrame(MechanismAssembly assembly, BlockPos miniPos) {
        BlockPos logicalOffset = new BlockPos(
                Math.floorDiv(miniPos.getX(), CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getY(), CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getZ(), CELLS_PER_FRAME_AXIS));
        return assembly.physicalFrameAt(logicalOffset);
    }

    public static boolean isOwnedMiniPosition(MechanismAssembly assembly, BlockPos miniPos) {
        return assembly.containsFrame(miniToFrame(assembly, miniPos));
    }

    /** Returns logical 0/1 cell coordinates inside the owning logical Frame. */
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
