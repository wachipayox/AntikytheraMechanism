package dev.antikytheramechanism.compat.create;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContraptionRotationMathTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void reconstructsAProperRotationFromTransformedBasisVectors() {
        Quaterniond rotation = ContraptionRotationMath.fromBasis(
                        new Vector3d(0.0, 1.0, 0.0),
                        new Vector3d(-1.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0))
                .orElseThrow();

        Vector3d transformed = rotation.transform(new Vector3d(1.0, 0.0, 0.0));
        assertEquals(0.0, transformed.x, EPSILON);
        assertEquals(1.0, transformed.y, EPSILON);
        assertEquals(0.0, transformed.z, EPSILON);
    }

    @Test
    void rejectsScaleShearAndReflections() {
        assertTrue(ContraptionRotationMath.fromBasis(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 1.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0)).isEmpty());
        assertTrue(ContraptionRotationMath.fromBasis(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 1.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0)).isEmpty());
    }
}
