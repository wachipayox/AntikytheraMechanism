package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Stable world transform of an assembly's logical origin.
 *
 * <p>This is deliberately independent from Sable's rigid-body position, whose
 * reference point can change when mass and bounds are recalculated.</p>
 */
public record AssemblyPose(
        double anchorX,
        double anchorY,
        double anchorZ,
        double quaternionX,
        double quaternionY,
        double quaternionZ,
        double quaternionW) {
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-20;

    public AssemblyPose {
        if (!Double.isFinite(anchorX)
                || !Double.isFinite(anchorY)
                || !Double.isFinite(anchorZ)
                || !Double.isFinite(quaternionX)
                || !Double.isFinite(quaternionY)
                || !Double.isFinite(quaternionZ)
                || !Double.isFinite(quaternionW)) {
            throw new IllegalArgumentException("Assembly pose components must be finite");
        }
        double lengthSquared = quaternionX * quaternionX
                + quaternionY * quaternionY
                + quaternionZ * quaternionZ
                + quaternionW * quaternionW;
        if (lengthSquared < MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException("Assembly pose orientation must be non-zero");
        }
        if (Math.abs(lengthSquared - 1.0) > 1.0E-12) {
            double inverseLength = 1.0 / Math.sqrt(lengthSquared);
            quaternionX *= inverseLength;
            quaternionY *= inverseLength;
            quaternionZ *= inverseLength;
            quaternionW *= inverseLength;
        }
    }

    public static AssemblyPose identityAt(BlockPos origin) {
        return new AssemblyPose(
                origin.getX() + 0.5,
                origin.getY() + 0.5,
                origin.getZ() + 0.5,
                0.0,
                0.0,
                0.0,
                1.0);
    }

    public static AssemblyPose of(Vector3dc anchor, Quaterniondc orientation) {
        return new AssemblyPose(
                anchor.x(),
                anchor.y(),
                anchor.z(),
                orientation.x(),
                orientation.y(),
                orientation.z(),
                orientation.w());
    }

    public Vector3d anchor(Vector3d destination) {
        return destination.set(anchorX, anchorY, anchorZ);
    }

    public Quaterniond orientation(Quaterniond destination) {
        return destination.set(quaternionX, quaternionY, quaternionZ, quaternionW);
    }

    public AssemblyPose rebased(BlockPos previousOrigin, BlockPos newOrigin) {
        Vector3d offset = new Vector3d(
                newOrigin.getX() - previousOrigin.getX(),
                newOrigin.getY() - previousOrigin.getY(),
                newOrigin.getZ() - previousOrigin.getZ());
        orientation(new Quaterniond()).transform(offset);
        return new AssemblyPose(
                anchorX + offset.x,
                anchorY + offset.y,
                anchorZ + offset.z,
                quaternionX,
                quaternionY,
                quaternionZ,
                quaternionW);
    }

    public AssemblyPose translated(Vector3dc offset) {
        return new AssemblyPose(
                anchorX + offset.x(),
                anchorY + offset.y(),
                anchorZ + offset.z(),
                quaternionX,
                quaternionY,
                quaternionZ,
                quaternionW);
    }

    public boolean approximatelyEquals(AssemblyPose other, double epsilon) {
        return Math.abs(anchorX - other.anchorX) <= epsilon
                && Math.abs(anchorY - other.anchorY) <= epsilon
                && Math.abs(anchorZ - other.anchorZ) <= epsilon
                && Math.abs(quaternionX - other.quaternionX) <= epsilon
                && Math.abs(quaternionY - other.quaternionY) <= epsilon
                && Math.abs(quaternionZ - other.quaternionZ) <= epsilon
                && Math.abs(quaternionW - other.quaternionW) <= epsilon;
    }

    /**
     * Docked assembly origins live in the physical parent grid. Once the mini axes may have a
     * separate discrete orientation, the physical origin delta must not be rotated a second time.
     */
    public boolean isCompatibleWhenRebasedTo(
            BlockPos thisOrigin,
            AssemblyPose other,
            BlockPos otherOrigin,
            double epsilon) {
        BlockPos delta = otherOrigin.subtract(thisOrigin);
        if (Math.abs(anchorX + delta.getX() - other.anchorX) > epsilon
                || Math.abs(anchorY + delta.getY() - other.anchorY) > epsilon
                || Math.abs(anchorZ + delta.getZ() - other.anchorZ) > epsilon) {
            return false;
        }
        double dot = quaternionX * other.quaternionX
                + quaternionY * other.quaternionY
                + quaternionZ * other.quaternionZ
                + quaternionW * other.quaternionW;
        return Math.abs(Math.abs(dot) - 1.0) <= epsilon;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("anchor_x", anchorX);
        tag.putDouble("anchor_y", anchorY);
        tag.putDouble("anchor_z", anchorZ);
        tag.putDouble("quaternion_x", quaternionX);
        tag.putDouble("quaternion_y", quaternionY);
        tag.putDouble("quaternion_z", quaternionZ);
        tag.putDouble("quaternion_w", quaternionW);
        return tag;
    }

    public static AssemblyPose load(CompoundTag tag, AssemblyPose fallback) {
        if (!tag.contains("anchor_x") || !tag.contains("quaternion_w")) {
            return fallback;
        }
        try {
            return new AssemblyPose(
                    tag.getDouble("anchor_x"),
                    tag.getDouble("anchor_y"),
                    tag.getDouble("anchor_z"),
                    tag.getDouble("quaternion_x"),
                    tag.getDouble("quaternion_y"),
                    tag.getDouble("quaternion_z"),
                    tag.getDouble("quaternion_w"));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
