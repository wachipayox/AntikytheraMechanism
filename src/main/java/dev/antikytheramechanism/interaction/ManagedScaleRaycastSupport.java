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
}
