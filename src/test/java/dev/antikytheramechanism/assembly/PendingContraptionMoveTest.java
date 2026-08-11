package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingContraptionMoveTest {
    @Test
    void captureJournalRoundTripsBeforePlacement() {
        PendingContraptionMove move = capturedMove();

        PendingContraptionMove loaded = PendingContraptionMove.load(move.save());

        assertEquals(move.assemblyId(), loaded.assemblyId());
        assertEquals(move.sourceFrames(), loaded.sourceFrames());
        assertEquals(move.sourceOrigin(), loaded.sourceOrigin());
        assertEquals(move.localFrames(), loaded.localFrames());
        assertEquals(move.startPose(), loaded.startPose());
        assertEquals(move.startedTick(), loaded.startedTick());
        assertFalse(loaded.hasPlacement());
    }

    @Test
    void translatedPlacementRoundTripsWithExactFinalPose() {
        PendingContraptionMove captured = capturedMove();
        BlockPos delta = new BlockPos(7, -2, 4);
        Set<BlockPos> targets = Set.of(
                new BlockPos(17, 18, 34),
                new BlockPos(18, 18, 34));
        AssemblyPose finalPose = new AssemblyPose(
                18.5, 18.5, 34.5,
                0.0, Math.sqrt(0.5), 0.0, Math.sqrt(0.5));

        PendingContraptionMove placed = captured.withPlacement(
                targets,
                new BlockPos(18, 18, 34),
                finalPose);
        PendingContraptionMove loaded = PendingContraptionMove.load(placed.save());

        assertTrue(loaded.hasPlacement());
        assertEquals(delta, loaded.delta());
        assertEquals(targets, loaded.targetFrames());
        assertEquals(finalPose, loaded.finalPose());
        assertTrue(targets.stream().allMatch(loaded::covers));
    }

    @Test
    void rejectsRotationOfAMultiFrameLogicalMapping() {
        PendingContraptionMove captured = capturedMove();

        assertThrows(IllegalArgumentException.class, () -> captured.withPlacement(
                Set.of(new BlockPos(20, 20, 30), new BlockPos(20, 21, 30)),
                new BlockPos(20, 21, 30),
                AssemblyPose.identityAt(new BlockPos(20, 21, 30))));
    }

    @Test
    void oneFrameAssemblyMayRelocateAfterDiscreteRotation() {
        UUID id = UUID.randomUUID();
        PendingContraptionMove captured = new PendingContraptionMove(
                id,
                Set.of(new BlockPos(2, 3, 4)),
                new BlockPos(2, 3, 4),
                Set.of(new BlockPos(-5, 1, 8)),
                AssemblyPose.identityAt(new BlockPos(2, 3, 4)),
                9L);

        PendingContraptionMove placed = captured.withPlacement(
                Set.of(new BlockPos(-10, 40, 7)),
                new BlockPos(-10, 40, 7),
                AssemblyPose.identityAt(new BlockPos(-10, 40, 7)));

        assertEquals(new BlockPos(-12, 37, 3), placed.delta());
    }

    @Test
    void rejectsPartiallyPersistedPlacementTarget() {
        CompoundTag incomplete = capturedMove().save();
        incomplete.putLong("target_origin", BlockPos.ZERO.asLong());

        assertThrows(IllegalArgumentException.class, () -> PendingContraptionMove.load(incomplete));
    }

    @Test
    void preservesAnOriginWhoseOriginalFrameWasRemoved() {
        PendingContraptionMove captured = new PendingContraptionMove(
                UUID.randomUUID(),
                Set.of(new BlockPos(11, 20, 30)),
                new BlockPos(10, 20, 30),
                Set.of(new BlockPos(1, 0, 0)),
                AssemblyPose.identityAt(new BlockPos(10, 20, 30)),
                77L);

        PendingContraptionMove placed = captured.withPlacement(
                Set.of(new BlockPos(16, 18, 34)),
                new BlockPos(15, 18, 34),
                AssemblyPose.identityAt(new BlockPos(15, 18, 34)));

        assertEquals(new BlockPos(5, -2, 4), placed.delta());
        assertEquals(placed.targetOrigin(), PendingContraptionMove.load(placed.save()).targetOrigin());
    }

    private static PendingContraptionMove capturedMove() {
        UUID id = UUID.fromString("e985dbcf-f121-4d2e-88eb-9423a67dba2c");
        Set<BlockPos> sources = Set.of(
                new BlockPos(10, 20, 30),
                new BlockPos(11, 20, 30));
        Set<BlockPos> locals = Set.of(
                new BlockPos(-3, 0, 5),
                new BlockPos(-2, 0, 5));
        return new PendingContraptionMove(
                id,
                sources,
                new BlockPos(11, 20, 30),
                locals,
                AssemblyPose.identityAt(new BlockPos(11, 20, 30)),
                1234L);
    }
}
