package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameOrientationContinuousMappingTest {
    private static final double EPSILON = 1.0E-12;

    @Test
    void allHorizontalOrientationsRoundTripContinuousFrameLocalPoints() {
        Vector3d[] logicalPoints = {
                new Vector3d(0.125, 0.250, 0.375),
                new Vector3d(0.875, 0.750, 0.625),
                new Vector3d(0.250, 0.875, 0.750),
                new Vector3d(0.625, 0.125, 0.875)
        };

        for (Direction front : Direction.Plane.HORIZONTAL) {
            FrameOrientation orientation = new FrameOrientation(front);
            for (Vector3d logical : logicalPoints) {
                Vector3d physical = orientation.logicalLocalToPhysical(
                        logical.x, logical.y, logical.z, new Vector3d());
                Vector3d roundTrip = orientation.physicalLocalToLogical(
                        physical.x, physical.y, physical.z, new Vector3d());

                assertEquals(logical.x, roundTrip.x, EPSILON, "x front=" + front);
                assertEquals(logical.y, roundTrip.y, EPSILON, "y front=" + front);
                assertEquals(logical.z, roundTrip.z, EPSILON, "z front=" + front);
            }
        }
    }

    @Test
    void physicalCursorOctantSurvivesPhysicalToLogicalPlacementMapping() {
        for (Direction front : Direction.Plane.HORIZONTAL) {
            FrameOrientation orientation = new FrameOrientation(front);
            for (int physicalX = 0; physicalX < 2; physicalX++) {
                for (int physicalY = 0; physicalY < 2; physicalY++) {
                    for (int physicalZ = 0; physicalZ < 2; physicalZ++) {
                        // Deliberately asymmetric offsets catch transpose/sign mistakes that octant
                        // centres alone can hide under quarter-turn rotations.
                        double x = (physicalX + 0.20) * 0.5;
                        double y = (physicalY + 0.65) * 0.5;
                        double z = (physicalZ + 0.80) * 0.5;
                        Vector3d logical = orientation.physicalLocalToLogical(x, y, z, new Vector3d());

                        int logicalX = logical.x >= 0.5 ? 1 : 0;
                        int logicalY = logical.y >= 0.5 ? 1 : 0;
                        int logicalZ = logical.z >= 0.5 ? 1 : 0;
                        BlockPos physicalAgain = orientation.logicalCellToPhysical(
                                logicalX, logicalY, logicalZ);

                        assertEquals(
                                new BlockPos(physicalX, physicalY, physicalZ),
                                physicalAgain,
                                "front=" + front + " physical=" + physicalX + "," + physicalY + "," + physicalZ);
                    }
                }
            }
        }
    }
}
