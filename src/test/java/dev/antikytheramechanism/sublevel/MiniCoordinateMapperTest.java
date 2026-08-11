package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniCoordinateMapperTest {
    @Test
    void mapsExactlyTwoByTwoByTwoCellsForOneFrame() {
        MechanismAssembly assembly = new MechanismAssembly(UUID.randomUUID(), new BlockPos(10, 20, 30));
        BlockPos frame = new BlockPos(9, 22, 27);
        Set<BlockPos> mappedCells = new HashSet<>();

        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                    mappedCells.add(mini);

                    assertEquals(new BlockPos(-2 + x, 4 + y, -6 + z), mini);
                    assertEquals(frame, MiniCoordinateMapper.miniToFrame(assembly, mini));
                    assertEquals(new BlockPos(x, y, z), MiniCoordinateMapper.cellInFrame(mini));
                }
            }
        }

        assertEquals(8, mappedCells.size());
    }

    @Test
    void usesFloorDivisionAndFloorModForNegativeMiniCoordinates() {
        MechanismAssembly assembly = new MechanismAssembly(UUID.randomUUID(), new BlockPos(5, 40, -7));

        assertEquals(
                assembly.origin().offset(-1, -1, -2),
                MiniCoordinateMapper.miniToFrame(assembly, new BlockPos(-1, -2, -3)));
        assertEquals(new BlockPos(1, 0, 1), MiniCoordinateMapper.cellInFrame(new BlockPos(-1, -2, -3)));
    }

    @Test
    void cellMaskAddressesEachOfTheEightCellsExactlyOnce() {
        Set<Integer> indices = new HashSet<>();
        int fullMask = 0;

        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    int index = MiniCoordinateMapper.cellIndex(x, y, z);
                    indices.add(index);
                    fullMask |= 1 << index;
                }
            }
        }

        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6, 7), indices);
        assertEquals(0xFF, fullMask);
        assertTrue(MiniCoordinateMapper.isCellOccupied(fullMask, 0, 0, 0));
        assertTrue(MiniCoordinateMapper.isCellOccupied(fullMask, 1, 1, 1));
        assertFalse(MiniCoordinateMapper.isCellOccupied(0, 1, 1, 1));
    }

    @Test
    void rejectsCoordinatesOutsideAFrameCellRange() {
        MechanismAssembly assembly = new MechanismAssembly(UUID.randomUUID(), BlockPos.ZERO);

        assertThrows(
                IllegalArgumentException.class,
                () -> MiniCoordinateMapper.frameToMini(assembly, BlockPos.ZERO, -1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniCoordinateMapper.frameToMini(assembly, BlockPos.ZERO, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> MiniCoordinateMapper.cellIndex(0, 0, 2));
    }
}
