package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyPoseTest {
    @Test
    void identityPoseUsesFrameCenter() {
        AssemblyPose pose = AssemblyPose.identityAt(new BlockPos(-3, 7, 11));

        assertEquals(-2.5, pose.anchorX());
        assertEquals(7.5, pose.anchorY());
        assertEquals(11.5, pose.anchorZ());
        assertEquals(1.0, pose.quaternionW());
    }

    @Test
    void orientationIsNormalizedAndRoundTripsThroughNbt() {
        AssemblyPose pose = new AssemblyPose(1.25, -2.5, 8.0, 0.0, 2.0, 0.0, 2.0);
        AssemblyPose loaded = AssemblyPose.load(pose.save(), AssemblyPose.identityAt(BlockPos.ZERO));

        assertEquals(pose, loaded);
        assertEquals(1.0, pose.orientation(new Quaterniond()).lengthSquared(), 1.0E-12);
    }

    @Test
    void rebasingRotatesTheOriginOffsetWithoutChangingOrientation() {
        Quaterniond quarterTurn = new Quaterniond().rotateY(Math.PI / 2.0);
        AssemblyPose pose = AssemblyPose.of(new Vector3d(10.0, 20.0, 30.0), quarterTurn);
        AssemblyPose rebased = pose.rebased(BlockPos.ZERO, new BlockPos(1, 0, 0));

        Vector3d expectedOffset = quarterTurn.transform(new Vector3d(1.0, 0.0, 0.0));
        assertEquals(10.0 + expectedOffset.x, rebased.anchorX(), 1.0E-12);
        assertEquals(20.0 + expectedOffset.y, rebased.anchorY(), 1.0E-12);
        assertEquals(30.0 + expectedOffset.z, rebased.anchorZ(), 1.0E-12);
        assertTrue(pose.orientation(new Quaterniond()).equals(rebased.orientation(new Quaterniond()), 1.0E-12));
    }

    @Test
    void mergeCompatibilityRequiresOneRebasedRigidTransform() {
        BlockPos leftOrigin = new BlockPos(4, 8, 12);
        BlockPos rightOrigin = leftOrigin.east();
        Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 2.0);
        AssemblyPose left = AssemblyPose.of(new Vector3d(20.0, 30.0, 40.0), rotation);
        AssemblyPose right = left.rebased(leftOrigin, rightOrigin);

        assertTrue(left.isCompatibleWhenRebasedTo(leftOrigin, right, rightOrigin, 1.0E-10));
        AssemblyPose displaced = right.translated(new Vector3d(0.01, 0.0, 0.0));
        org.junit.jupiter.api.Assertions.assertFalse(
                left.isCompatibleWhenRebasedTo(leftOrigin, displaced, rightOrigin, 1.0E-6));

        AssemblyPose sameWithOppositeQuaternion = new AssemblyPose(
                right.anchorX(),
                right.anchorY(),
                right.anchorZ(),
                -right.quaternionX(),
                -right.quaternionY(),
                -right.quaternionZ(),
                -right.quaternionW());
        assertTrue(left.isCompatibleWhenRebasedTo(
                leftOrigin,
                sameWithOppositeQuaternion,
                rightOrigin,
                1.0E-10));
    }

    @Test
    void rejectsNonFiniteAndZeroOrientations() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssemblyPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new AssemblyPose(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0));
    }
}
