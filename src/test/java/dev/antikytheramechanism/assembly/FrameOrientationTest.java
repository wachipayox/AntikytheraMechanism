package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameOrientationTest {
    @Test
    void allTwentyFourOrientationsRoundTripCoordinatesDirectionsCellsAndQuaternions() {
        int count = 0;
        for (Direction up : Direction.values()) {
            for (Direction front : Direction.values()) {
                if (up.getAxis() == front.getAxis()) continue;
                FrameOrientation orientation = new FrameOrientation(up, front);
                count++;

                for (BlockPos logical : new BlockPos[]{
                        BlockPos.ZERO, new BlockPos(1, 2, 3), new BlockPos(-4, 5, -6)}) {
                    assertEquals(logical, orientation.toLogical(orientation.toPhysical(logical)));
                }
                for (Direction logical : Direction.values()) {
                    assertEquals(logical, orientation.toLogical(orientation.toPhysical(logical)));
                }
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    BlockPos logical = new BlockPos(x, y, z);
                    BlockPos physical = orientation.logicalCellToPhysical(x, y, z);
                    assertEquals(logical, orientation.physicalCellToLogical(
                            physical.getX(), physical.getY(), physical.getZ()));
                }
                assertEquals(orientation,
                        FrameOrientation.fromQuaternion(orientation.quaternion(new Quaterniond())).orElseThrow());
            }
        }
        assertEquals(24, count);
    }
}
