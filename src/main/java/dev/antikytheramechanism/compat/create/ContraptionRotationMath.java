package dev.antikytheramechanism.compat.create;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Optional;

/** Converts Create's transformed basis vectors into a validated rotation. */
public final class ContraptionRotationMath {
    private static final double ORTHONORMAL_EPSILON = 1.0E-6;

    private ContraptionRotationMath() {
    }

    public static Optional<Quaterniond> fromBasis(Vector3dc xBasis, Vector3dc yBasis, Vector3dc zBasis) {
        Vector3d x = normalizedFinite(xBasis);
        Vector3d y = normalizedFinite(yBasis);
        Vector3d z = normalizedFinite(zBasis);
        if (x == null || y == null || z == null) {
            return Optional.empty();
        }
        if (Math.abs(x.dot(y)) > ORTHONORMAL_EPSILON
                || Math.abs(x.dot(z)) > ORTHONORMAL_EPSILON
                || Math.abs(y.dot(z)) > ORTHONORMAL_EPSILON) {
            return Optional.empty();
        }
        double determinant = new Vector3d(x).cross(y).dot(z);
        if (!Double.isFinite(determinant) || Math.abs(determinant - 1.0) > ORTHONORMAL_EPSILON) {
            return Optional.empty();
        }

        Matrix3d matrix = new Matrix3d()
                .setColumn(0, x)
                .setColumn(1, y)
                .setColumn(2, z);
        Quaterniond result = new Quaterniond().setFromNormalized(matrix).normalize();
        canonicalizeSign(result);
        return Optional.of(result);
    }

    private static Vector3d normalizedFinite(Vector3dc source) {
        if (!Double.isFinite(source.x()) || !Double.isFinite(source.y()) || !Double.isFinite(source.z())) {
            return null;
        }
        double lengthSquared = source.lengthSquared();
        if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0E-20) {
            return null;
        }
        return new Vector3d(source).normalize();
    }

    private static void canonicalizeSign(Quaterniond quaternion) {
        boolean negate = quaternion.w < 0.0
                || quaternion.w == 0.0 && quaternion.x < 0.0
                || quaternion.w == 0.0 && quaternion.x == 0.0 && quaternion.y < 0.0
                || quaternion.w == 0.0 && quaternion.x == 0.0 && quaternion.y == 0.0 && quaternion.z < 0.0;
        if (negate) {
            quaternion.set(-quaternion.x, -quaternion.y, -quaternion.z, -quaternion.w);
        }
    }
}
