package dev.antikytheramechanism.interaction;

import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3dc;

/** Runtime state and coordinate helpers for the managed scaled raycast correction. */
public final class ManagedScaleRaycastSupport {
    private static final ThreadLocal<Boolean> REENTRY = ThreadLocal.withInitial(() -> false);
    private static final double DISTANCE_EPSILON_SQUARED = 1.0E-8;
    private static final double RAY_PARAMETER_EPSILON = 1.0E-7;
    private static final double HIT_BOX_EPSILON = 1.0E-6;

    /**
     * Small physical-space envelope around the exact Frame bar used only while arbitrating an
     * already-existing exact Frame hit against a managed mini hit. It never changes getShape(), the
     * outline, collision or the area in which a Frame can become a candidate.
     */
    static final double FRAME_OCCLUSION_ENVELOPE = 1.0 / 64.0;

    private ManagedScaleRaycastSupport() {
    }

    public static boolean isReentrant() {
        return REENTRY.get();
    }

    public static void beginReentry() {
        REENTRY.set(true);
    }

    public static void endReentry() {
        REENTRY.remove();
    }

    public static Vec3 projectHitLocation(Level level, SubLevel subLevel, Vec3 localLocation) {
        Pose3dc pose = pose(level, subLevel);
        Vector3dc projected = pose.transformPosition(JOMLConversion.toJOML(localLocation));
        return JOMLConversion.toMojang(projected);
    }

    /** Converts a world-space point into the local coordinate space of a Sable SubLevel. */
    public static Vec3 unprojectWorldLocation(Level level, SubLevel subLevel, Vec3 worldLocation) {
        Pose3dc pose = pose(level, subLevel);
        Vector3dc local = pose.transformPositionInverse(JOMLConversion.toJOML(worldLocation));
        return JOMLConversion.toMojang(local);
    }

    /** Strict nearest-hit arbitration used for ordinary physical blocks. */
    public static boolean shouldPreferPhysicalCandidate(
            double candidateDistanceSquared,
            double managedDistanceSquared) {
        return candidateDistanceSquared <= managedDistanceSquared + DISTANCE_EPSILON_SQUARED;
    }

    /**
     * Resolves a real Mechanism Frame bar against a managed mini hit without widening the Frame's
     * selection shape.
     *
     * <p>The exact Frame raycast has already proved that the crosshair intersects a real 2/16 bar.
     * We identify the concrete AABB(s) touched at that hit and inflate only those boxes by 1/64 for
     * render-pose jitter. From that point on the bar behaves like an ordinary visual occluder: a mini
     * hit before the bar remains selectable, while every mini hit at or behind the bar is hidden by
     * the Frame. This is angle invariant and, unlike the previous finite bar-interval rule, cannot
     * hand selection back to a deeper mini hit merely because its impact point lies beyond the rear
     * face of the bar.</p>
     */
    public static boolean shouldPreferFrameCandidate(
            Vec3 rayStart,
            Vec3 rayEnd,
            BlockPos framePos,
            VoxelShape exactFrameShape,
            Vec3 exactFrameHitLocation,
            Vec3 managedHitLocation) {
        Vec3 ray = rayEnd.subtract(rayStart);
        double rayLengthSquared = ray.lengthSqr();
        if (rayLengthSquared <= 1.0E-12) {
            return exactFrameHitLocation.distanceToSqr(rayStart)
                    <= managedHitLocation.distanceToSqr(rayStart) + DISTANCE_EPSILON_SQUARED;
        }

        double frameT = rayParameter(rayStart, ray, rayLengthSquared, exactFrameHitLocation);
        double managedT = rayParameter(rayStart, ray, rayLengthSquared, managedHitLocation);
        double occlusionStart = Double.POSITIVE_INFINITY;

        for (AABB localBox : exactFrameShape.toAabbs()) {
            AABB exactBox = localBox.move(framePos.getX(), framePos.getY(), framePos.getZ());
            if (!containsWithEpsilon(exactBox, exactFrameHitLocation, HIT_BOX_EPSILON)) {
                continue;
            }

            AABB arbitrationBox = exactBox.inflate(FRAME_OCCLUSION_ENVELOPE);
            double[] interval = rayAabbInterval(rayStart, ray, arbitrationBox);
            if (interval == null
                    || interval[0] > frameT + RAY_PARAMETER_EPSILON
                    || interval[1] < frameT - RAY_PARAMETER_EPSILON) {
                continue;
            }

            occlusionStart = Math.min(occlusionStart, interval[0]);
        }

        if (occlusionStart == Double.POSITIVE_INFINITY) {
            // Defensive fallback if a mod interaction override returned a point that cannot be mapped
            // back to one of the Frame's shape boxes. Do not invent Frame priority in that case.
            return exactFrameHitLocation.distanceToSqr(rayStart)
                    <= managedHitLocation.distanceToSqr(rayStart) + DISTANCE_EPSILON_SQUARED;
        }

        return managedT >= occlusionStart - RAY_PARAMETER_EPSILON;
    }

    private static Pose3dc pose(Level level, SubLevel subLevel) {
        Pose3dc pose = subLevel.logicalPose();
        if (level instanceof LevelPoseProviderExtension extension) {
            pose = extension.sable$getPose(subLevel);
        }
        return pose;
    }

    private static double rayParameter(Vec3 rayStart, Vec3 ray, double rayLengthSquared, Vec3 point) {
        return point.subtract(rayStart).dot(ray) / rayLengthSquared;
    }

    private static boolean containsWithEpsilon(AABB box, Vec3 point, double epsilon) {
        return point.x >= box.minX - epsilon && point.x <= box.maxX + epsilon
                && point.y >= box.minY - epsilon && point.y <= box.maxY + epsilon
                && point.z >= box.minZ - epsilon && point.z <= box.maxZ + epsilon;
    }

    /** Returns the clipped [entry, exit] parameter interval for start + t * ray, or null. */
    private static double[] rayAabbInterval(Vec3 start, Vec3 ray, AABB box) {
        double tMin = 0.0;
        double tMax = 1.0;

        double[] x = clipAxis(start.x, ray.x, box.minX, box.maxX, tMin, tMax);
        if (x == null) return null;
        tMin = x[0];
        tMax = x[1];

        double[] y = clipAxis(start.y, ray.y, box.minY, box.maxY, tMin, tMax);
        if (y == null) return null;
        tMin = y[0];
        tMax = y[1];

        return clipAxis(start.z, ray.z, box.minZ, box.maxZ, tMin, tMax);
    }

    private static double[] clipAxis(
            double start,
            double delta,
            double min,
            double max,
            double currentMin,
            double currentMax) {
        if (Math.abs(delta) <= 1.0E-12) {
            if (start < min || start > max) {
                return null;
            }
            return new double[]{currentMin, currentMax};
        }

        double t1 = (min - start) / delta;
        double t2 = (max - start) / delta;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
        }

        double nextMin = Math.max(currentMin, t1);
        double nextMax = Math.min(currentMax, t2);
        if (nextMin > nextMax) {
            return null;
        }
        return new double[]{nextMin, nextMax};
    }
}
