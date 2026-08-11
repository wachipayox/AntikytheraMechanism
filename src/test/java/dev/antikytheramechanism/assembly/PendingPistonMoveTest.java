package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingPistonMoveTest {
    @Test
    void derivesDestinationsAndPoseFromOneBlockTranslation() {
        AssemblyPose start = AssemblyPose.identityAt(new BlockPos(10, 20, 30));
        PendingPistonMove move = new PendingPistonMove(
                UUID.fromString("9c64db43-3491-42b1-84f7-793a348c3042"),
                new BlockPos(8, 20, 30),
                new BlockPos(1, 0, 0),
                Set.of(new BlockPos(10, 20, 30), new BlockPos(10, 21, 30)),
                start,
                100L,
                true);

        assertEquals(Direction.EAST, move.movementDirection());
        assertEquals(
                Set.of(new BlockPos(11, 20, 30), new BlockPos(11, 21, 30)),
                move.destinationFrames());
        assertEquals(11.0, move.poseAtProgress(0.5).anchorX());
        assertEquals(start.anchorY(), move.poseAtProgress(0.5).anchorY());
        assertEquals(11.5, move.poseAtProgress(1.0).anchorX());
    }

    @Test
    void journalRoundTripsThroughNbt() {
        PendingPistonMove original = new PendingPistonMove(
                UUID.fromString("528bbf13-a67f-48b6-a51a-e9b1e4c7512f"),
                new BlockPos(-4, 7, 9),
                new BlockPos(0, -1, 0),
                Set.of(new BlockPos(-4, 6, 9), new BlockPos(-3, 6, 9)),
                AssemblyPose.identityAt(new BlockPos(-4, 6, 9)),
                456L,
                false);

        PendingPistonMove loaded = PendingPistonMove.load(original.save());

        assertEquals(original.assemblyId(), loaded.assemblyId());
        assertEquals(original.pistonPosition(), loaded.pistonPosition());
        assertEquals(original.delta(), loaded.delta());
        assertEquals(original.sourceFrames(), loaded.sourceFrames());
        assertEquals(original.destinationFrames(), loaded.destinationFrames());
        assertEquals(original.startPose(), loaded.startPose());
        assertEquals(original.startedTick(), loaded.startedTick());
        assertEquals(original.extending(), loaded.extending());
    }

    @Test
    void rejectsAnythingExceptAUnitAxisTranslation() {
        assertThrows(IllegalArgumentException.class, () -> new PendingPistonMove(
                UUID.randomUUID(),
                BlockPos.ZERO,
                new BlockPos(1, 1, 0),
                Set.of(BlockPos.ZERO),
                AssemblyPose.identityAt(BlockPos.ZERO),
                0L,
                true));
    }

    @Test
    void stickyRetractionRejectsOldHeadCarrierAtFrameDestination() {
        PendingPistonMove pull = new PendingPistonMove(
                UUID.randomUUID(),
                new BlockPos(49, 100, 0),
                new BlockPos(-1, 0, 0),
                Set.of(new BlockPos(51, 100, 0)),
                AssemblyPose.identityAt(new BlockPos(51, 100, 0)),
                200L,
                false);
        BlockPos destination = new BlockPos(50, 100, 0);

        assertEquals(true, pull.matchesCarrierMetadata(destination, Direction.WEST, false, false));
        assertEquals(false, pull.matchesCarrierMetadata(destination, Direction.WEST, false, true));
        assertEquals(false, pull.matchesCarrierMetadata(destination, Direction.EAST, true, true));
        assertEquals(false, pull.matchesCarrierMetadata(new BlockPos(49, 100, 0), Direction.WEST, false, false));
    }
}
