package dev.antikytheramechanism.compat.sablescale;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Corrects Sable's center-of-mass pivot compensation for scaled SubLevels.
 *
 * <p>Sable keeps a vehicle visually stationary when its center of mass changes by moving the pose
 * position by {@code R * deltaCoM} before replacing the pose rotation point. That is exact only when
 * the pose scale is identity. For the actual Sable transform
 * {@code world = position + R * S * (local - rotationPoint)}, changing the rotation point by
 * {@code deltaCoM} requires moving the pose position by {@code R * S * deltaCoM}.</p>
 *
 * <p>The caller passes the local delta before Sable rotates it, so applying the component-wise pose
 * scale here is sufficient and works for both uniform and non-uniform scales.</p>
 */
public final class SableScalePivotCompensation {
    private SableScalePivotCompensation() {
    }

    /** Applies the missing local pose scale to Sable's mutable center-of-mass delta. */
    public static Vector3d applyLocalScale(Vector3d centerOfMassDelta, Vector3dc scale) {
        Objects.requireNonNull(centerOfMassDelta, "centerOfMassDelta");
        Objects.requireNonNull(scale, "scale");
        if (scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0) {
            return centerOfMassDelta;
        }
        return centerOfMassDelta.mul(scale);
    }
}
