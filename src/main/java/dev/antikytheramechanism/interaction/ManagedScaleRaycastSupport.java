package dev.antikytheramechanism.interaction;

import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

/** Runtime state and coordinate helpers for the managed scaled raycast correction. */
public final class ManagedScaleRaycastSupport {
    private static final ThreadLocal<Boolean> REENTRY = ThreadLocal.withInitial(() -> false);
    private static final double DISTANCE_EPSILON_SQUARED = 1.0E-8;

    /**
     * Tiny world-space allowance for a real Frame shell against its coplanar managed mini surface.
     * This is 1/64 of a block: enough to absorb render-pose interpolation, far smaller than one mini
     * cell, and it is applied only after the ray has actually intersected the Frame selection cage.
     */
    static final double FRAME_SHELL_PICK_TOLERANCE = 1.0 / 64.0;

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
        Pose3dc pose = subLevel.logicalPose();
        if (level instanceof LevelPoseProviderExtension extension) {
            pose = extension.sable$getPose(subLevel);
        }
        Vector3dc projected = pose.transformPosition(JOMLConversion.toJOML(localLocation));
        return JOMLConversion.toMojang(projected);
    }

    /**
     * Chooses a physical candidate against a managed mini hit. Ordinary blocks remain strict nearest
     * hit wins. A Mechanism Frame gets a very small linear tolerance because the outer mini surface
     * and cage are intentionally coplanar and the child may be using an interpolated render pose.
     */
    public static boolean shouldPreferPhysicalCandidate(
            boolean mechanismFrame,
            double candidateDistanceSquared,
            double managedDistanceSquared) {
        if (!mechanismFrame) {
            return candidateDistanceSquared <= managedDistanceSquared + DISTANCE_EPSILON_SQUARED;
        }
        return Math.sqrt(candidateDistanceSquared)
                <= Math.sqrt(managedDistanceSquared) + FRAME_SHELL_PICK_TOLERANCE;
    }
}
