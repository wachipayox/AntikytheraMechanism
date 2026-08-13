package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class AssemblyOrientationMath {
    private AssemblyOrientationMath() {}

    public static AssemblyPose rebaseLogical(AssemblyPose pose, BlockPos logicalOffset) {
        Vector3d offset = new Vector3d(logicalOffset.getX(), logicalOffset.getY(), logicalOffset.getZ());
        pose.orientation(new Quaterniond()).transform(offset);
        return new AssemblyPose(pose.anchorX() + offset.x, pose.anchorY() + offset.y, pose.anchorZ() + offset.z,
                pose.quaternionX(), pose.quaternionY(), pose.quaternionZ(), pose.quaternionW());
    }

    public static boolean compatiblePhysical(MechanismAssembly first, MechanismAssembly second, double epsilon) {
        if (!first.orientation().equals(second.orientation())) return false;
        BlockPos delta = second.origin().subtract(first.origin());
        AssemblyPose a = first.poseTarget();
        AssemblyPose b = second.poseTarget();
        if (Math.abs(a.anchorX() + delta.getX() - b.anchorX()) > epsilon
                || Math.abs(a.anchorY() + delta.getY() - b.anchorY()) > epsilon
                || Math.abs(a.anchorZ() + delta.getZ() - b.anchorZ()) > epsilon) return false;
        double dot = a.quaternionX() * b.quaternionX() + a.quaternionY() * b.quaternionY()
                + a.quaternionZ() * b.quaternionZ() + a.quaternionW() * b.quaternionW();
        return Math.abs(Math.abs(dot) - 1.0) <= epsilon;
    }
}
