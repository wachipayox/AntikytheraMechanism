package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingContraptionMoveTest {
    @Test
    void translatedPlacementRoundTrips() {
        PendingContraptionMove captured = capturedMove();
        BlockPos origin = new BlockPos(18, 18, 34);
        Set<BlockPos> targets = Set.of(new BlockPos(17, 18, 34), origin);
        PendingContraptionMove placed = captured.withPlacement(targets, origin, AssemblyPose.identityAt(origin));
        assertEquals(targets, PendingContraptionMove.load(placed.save()).targetFrames());
    }

    @Test
    void yawRotationPreservesLogicalMultiFrameShape() {
        PendingContraptionMove captured = capturedMove();
        BlockPos origin = new BlockPos(20, 20, 30);
        Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 2.0);
        FrameOrientation orientation = FrameOrientation.fromQuaternion(rotation).orElseThrow();
        Set<BlockPos> targets = captured.sourceFrames().stream()
                .map(position -> position.subtract(captured.sourceOrigin()))
                .map(orientation::toPhysical)
                .map(origin::offset)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AssemblyPose pose = AssemblyPose.of(new Vector3d(20.5, 20.5, 30.5), rotation);
        assertEquals(targets, captured.withPlacement(targets, origin, pose).targetFrames());
    }

    @Test
    void nonOrthogonalRotationIsRejected() {
        PendingContraptionMove captured = capturedMove();
        BlockPos origin = new BlockPos(20, 20, 30);
        AssemblyPose pose = AssemblyPose.of(new Vector3d(20.5, 20.5, 30.5),
                new Quaterniond().rotateY(Math.PI / 4.0));
        assertThrows(IllegalArgumentException.class,
                () -> captured.withPlacement(Set.of(origin, origin.east()), origin, pose));
    }

    @Test
    void captureJournalRoundTrips() {
        PendingContraptionMove captured = capturedMove();
        PendingContraptionMove loaded = PendingContraptionMove.load(captured.save());
        assertEquals(captured.sourceFrames(), loaded.sourceFrames());
        assertEquals(captured.localFrames(), loaded.localFrames());
        assertEquals(captured.startPose(), loaded.startPose());
    }

    private static PendingContraptionMove capturedMove() {
        return new PendingContraptionMove(
                UUID.fromString("e985dbcf-f121-4d2e-88eb-9423a67dba2c"),
                Set.of(new BlockPos(10, 20, 30), new BlockPos(11, 20, 30)),
                new BlockPos(11, 20, 30),
                Set.of(new BlockPos(-3, 0, 5), new BlockPos(-2, 0, 5)),
                AssemblyPose.identityAt(new BlockPos(11, 20, 30)),
                1234L);
    }
}
