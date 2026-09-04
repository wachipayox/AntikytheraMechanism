package dev.antikytheramechanism.compat.sablescale;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SableScalePivotCompensationTest {
    private static final double EPSILON = 1.0E-10;

    @Test
    void preservesWorldPositionAtHalfScale() {
        assertWorldPointInvariant(new Vector3d(0.5, 0.5, 0.5));
    }

    @Test
    void preservesWorldPositionAtDoubleScale() {
        assertWorldPointInvariant(new Vector3d(2.0, 2.0, 2.0));
    }

    @Test
    void preservesWorldPositionAtNonUniformScale() {
        assertWorldPointInvariant(new Vector3d(0.5, 1.75, 2.25));
    }

    private static void assertWorldPointInvariant(Vector3dc scale) {
        Vector3d position = new Vector3d(31.25, 72.5, -14.75);
        Vector3d oldPivot = new Vector3d(100.25, 64.5, -230.75);
        Vector3d newPivot = new Vector3d(101.75, 65.25, -228.5);
        Vector3d localPoint = new Vector3d(104.0, 67.0, -224.0);
        Quaterniond orientation = new Quaterniond().rotationXYZ(0.31, -0.82, 0.17);

        Vector3d worldBefore = worldPosition(position, orientation, scale, oldPivot, localPoint);

        Vector3d pivotDelta = new Vector3d(newPivot).sub(oldPivot);
        SableScalePivotCompensation.applyLocalScale(pivotDelta, scale);
        orientation.transform(pivotDelta);
        Vector3d correctedPosition = new Vector3d(position).add(pivotDelta);

        Vector3d worldAfter = worldPosition(correctedPosition, orientation, scale, newPivot, localPoint);
        assertEquals(worldBefore.x, worldAfter.x, EPSILON);
        assertEquals(worldBefore.y, worldAfter.y, EPSILON);
        assertEquals(worldBefore.z, worldAfter.z, EPSILON);
    }

    private static Vector3d worldPosition(
            Vector3dc position,
            Quaterniond orientation,
            Vector3dc scale,
            Vector3dc pivot,
            Vector3dc localPoint) {
        Vector3d localFromPivot = new Vector3d(localPoint).sub(pivot).mul(scale);
        orientation.transform(localFromPivot);
        return localFromPivot.add(position);
    }
}
