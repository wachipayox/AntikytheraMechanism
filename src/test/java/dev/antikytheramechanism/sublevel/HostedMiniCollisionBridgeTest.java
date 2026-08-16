package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostedMiniCollisionBridgeTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void fullMiniBlockIsScaledToOneEighthFrameVolume() {
        HostedMiniCollisionBridge.BoxKey box =
                HostedMiniCollisionBridge.transformMiniBox(
                        FrameOrientation.IDENTITY,
                        1, 0, 1,
                        0.0, 0.0, 0.0,
                        1.0, 1.0, 1.0);

        assertBox(box, 0.5, 0.0, 0.5, 1.0, 0.5, 1.0);
        assertEquals(0.125, box.volume(), EPSILON);
    }

    @Test
    void yawRotatesMiniCellAndItsShapeIntoPhysicalFrameAxes() {
        FrameOrientation yaw90 =
                FrameOrientation.IDENTITY.rotate(Direction.Axis.Y, 1);

        HostedMiniCollisionBridge.BoxKey box =
                HostedMiniCollisionBridge.transformMiniBox(
                        yaw90,
                        1, 0, 0,
                        0.0, 0.0, 0.0,
                        1.0, 1.0, 1.0);

        assertBox(box, 0.5, 0.0, 0.5, 1.0, 0.5, 1.0);
        assertEquals(0.125, box.volume(), EPSILON);
    }

    @Test
    void yawRotatesAnAsymmetricCollisionBoxRatherThanOnlyMovingItsCell() {
        FrameOrientation yaw90 =
                FrameOrientation.IDENTITY.rotate(Direction.Axis.Y, 1);

        HostedMiniCollisionBridge.BoxKey box =
                HostedMiniCollisionBridge.transformMiniBox(
                        yaw90,
                        1, 0, 0,
                        0.0, 0.0, 0.0,
                        0.25, 1.0, 1.0);

        assertBox(box, 0.5, 0.0, 0.5, 1.0, 0.5, 0.625);
        assertEquals(0.03125, box.volume(), EPSILON);
    }

    private static void assertBox(
            HostedMiniCollisionBridge.BoxKey box,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        assertEquals(minX, box.minX(), EPSILON);
        assertEquals(minY, box.minY(), EPSILON);
        assertEquals(minZ, box.minZ(), EPSILON);
        assertEquals(maxX, box.maxX(), EPSILON);
        assertEquals(maxY, box.maxY(), EPSILON);
        assertEquals(maxZ, box.maxZ(), EPSILON);
    }
}
