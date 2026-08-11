package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContraptionPoseBindingTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void findsTheTranslationForACompleteCapture() {
        Optional<BlockPos> translation = ContraptionPoseBinding.findTranslation(
                Set.of(new BlockPos(-2, 0, 4), new BlockPos(-1, 0, 4), new BlockPos(-1, 1, 4)),
                Set.of(new BlockPos(10, 20, 30), new BlockPos(11, 20, 30), new BlockPos(11, 21, 30)));

        assertEquals(Optional.of(new BlockPos(12, 20, 26)), translation);
    }

    @Test
    void rejectsPartialOrDifferentlyShapedCaptures() {
        Set<BlockPos> assembly = Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(1, 1, 0));

        assertTrue(ContraptionPoseBinding.findTranslation(
                Set.of(new BlockPos(5, 0, 0), new BlockPos(6, 0, 0)), assembly).isEmpty());
        assertTrue(ContraptionPoseBinding.findTranslation(
                Set.of(BlockPos.ZERO, new BlockPos(0, 1, 0), new BlockPos(1, 1, 0)), assembly).isEmpty());
    }

    @Test
    void followsTranslationAndRotationWithoutChangingMiniCoordinates() {
        MechanismAssembly assembly = new MechanismAssembly(
                UUID.randomUUID(),
                new BlockPos(11, 20, 30),
                Set.of(new BlockPos(10, 20, 30), new BlockPos(11, 20, 30)));
        ContraptionPoseBinding binding = ContraptionPoseBinding.initial(
                        assembly,
                        Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)),
                        BlockPos.ZERO)
                .orElseThrow();
        Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 2.0);
        Vector3d leaderCenter = new Vector3d(5.5, 6.5, 7.5);

        AssemblyPose pose = binding.poseAt(leaderCenter, rotation);
        Vector3d expectedOffset = rotation.transform(new Vector3d(1.0, 0.0, 0.0));
        assertEquals(leaderCenter.x + expectedOffset.x, pose.anchorX(), EPSILON);
        assertEquals(leaderCenter.y + expectedOffset.y, pose.anchorY(), EPSILON);
        assertEquals(leaderCenter.z + expectedOffset.z, pose.anchorZ(), EPSILON);
        Vector3d orientedX = pose.orientation(new Quaterniond()).transform(new Vector3d(1.0, 0.0, 0.0));
        assertEquals(expectedOffset.x, orientedX.x, EPSILON);
        assertEquals(expectedOffset.y, orientedX.y, EPSILON);
        assertEquals(expectedOffset.z, orientedX.z, EPSILON);
        assertEquals(new BlockPos(11, 20, 30), assembly.origin());
        assertEquals(Set.of(new BlockPos(10, 20, 30), new BlockPos(11, 20, 30)), assembly.frames());
    }

    @Test
    void roundTripsPersistentActorBinding() {
        UUID id = UUID.randomUUID();
        ContraptionPoseBinding binding = new ContraptionPoseBinding(
                id,
                new BlockPos(-4, 8, 12),
                1.0,
                -2.0,
                3.0,
                0.0,
                Math.sqrt(0.5),
                0.0,
                Math.sqrt(0.5));

        assertEquals(binding, ContraptionPoseBinding.load(binding.save()).orElseThrow());
        assertFalse(ContraptionPoseBinding.load(new CompoundTag()).isPresent());

        CompoundTag incomplete = binding.save();
        incomplete.remove("local_offset_z");
        assertFalse(ContraptionPoseBinding.load(incomplete).isPresent());
    }
}
