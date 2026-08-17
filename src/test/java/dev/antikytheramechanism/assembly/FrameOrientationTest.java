package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FrameOrientationTest {
    @Test
    void fourHorizontalOrientationsRoundTripCoordinatesDirectionsCellsAndQuaternions() {
        int count = 0;
        for (Direction front : Direction.Plane.HORIZONTAL) {
            FrameOrientation orientation = new FrameOrientation(front);
            count++;

            assertEquals(Direction.UP, orientation.up());
            assertEquals(Direction.UP, orientation.toPhysical(Direction.UP));
            assertEquals(Direction.DOWN, orientation.toPhysical(Direction.DOWN));
            assertEquals(Direction.UP, orientation.toLogical(Direction.UP));
            assertEquals(Direction.DOWN, orientation.toLogical(Direction.DOWN));

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
        assertEquals(4, count);
    }

    @Test
    void legacyPitchAndRollCanonicalizeToUprightYaw() {
        assertEquals(new FrameOrientation(Direction.SOUTH),
                new FrameOrientation(Direction.DOWN, Direction.SOUTH));
        assertEquals(new FrameOrientation(Direction.NORTH),
                new FrameOrientation(Direction.SOUTH, Direction.UP));
        assertEquals(new FrameOrientation(Direction.NORTH),
                new FrameOrientation(Direction.WEST, Direction.NORTH));

        Quaterniond upsideDown = new Quaterniond().rotateX(Math.PI);
        FrameOrientation canonical = FrameOrientation.fromQuaternion(upsideDown).orElseThrow();
        assertEquals(Direction.UP, canonical.up());
        assertEquals(Direction.SOUTH, canonical.front());
    }

    @Test
    void newNbtStoresOnlyYawAndLegacyNbtStillLoads() {
        FrameOrientation east = new FrameOrientation(Direction.EAST);
        CompoundTag current = east.save();
        assertFalse(current.contains("up"));
        assertEquals(east, FrameOrientation.load(current));

        CompoundTag legacyUpsideDown = new CompoundTag();
        legacyUpsideDown.putInt("up", Direction.DOWN.ordinal());
        legacyUpsideDown.putInt("front", Direction.SOUTH.ordinal());
        assertEquals(new FrameOrientation(Direction.SOUTH), FrameOrientation.load(legacyUpsideDown));

        CompoundTag legacyQuarterPitch = new CompoundTag();
        legacyQuarterPitch.putInt("up", Direction.SOUTH.ordinal());
        legacyQuarterPitch.putInt("front", Direction.UP.ordinal());
        assertEquals(new FrameOrientation(Direction.NORTH), FrameOrientation.load(legacyQuarterPitch));
    }
}
