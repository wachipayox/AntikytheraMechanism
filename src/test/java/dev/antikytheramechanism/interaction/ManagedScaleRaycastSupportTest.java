package dev.antikytheramechanism.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScaleRaycastSupportTest {
    private static final VoxelShape TWO_PIXEL_BAR = Block.box(0, 0, 0, 2, 16, 2);
    private static final Vec3 GRAZING_START = new Vec3(-0.1, 0.5, -1.0);
    private static final Vec3 GRAZING_END = new Vec3(0.1, 0.5, 1.0);
    private static final Vec3 EXACT_BAR_ENTRY = pointOnRay(0.5);

    @Test
    void grazingAngleMiniInsidePhysicalBarEnvelopeKeepsFrameStable() {
        Vec3 managed = pointOnRay(0.568);

        assertTrue(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                GRAZING_START,
                GRAZING_END,
                BlockPos.ZERO,
                TWO_PIXEL_BAR,
                EXACT_BAR_ENTRY,
                managed));
    }

    @Test
    void smallInterpolatedShiftInFrontOfExactBarStillKeepsFrameStable() {
        Vec3 managed = pointOnRay(0.495);

        assertTrue(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                GRAZING_START,
                GRAZING_END,
                BlockPos.ZERO,
                TWO_PIXEL_BAR,
                EXACT_BAR_ENTRY,
                managed));
    }

    @Test
    void deepMiniHitBehindBarRemainsOccluded() {
        Vec3 managed = pointOnRay(0.75);

        assertTrue(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                GRAZING_START,
                GRAZING_END,
                BlockPos.ZERO,
                TWO_PIXEL_BAR,
                EXACT_BAR_ENTRY,
                managed));
    }

    @Test
    void miniClearlyInFrontOfTheBarIsNotStolen() {
        Vec3 managed = pointOnRay(0.45);

        assertFalse(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                GRAZING_START,
                GRAZING_END,
                BlockPos.ZERO,
                TWO_PIXEL_BAR,
                EXACT_BAR_ENTRY,
                managed));
    }

    @Test
    void grazingNumericalMissRecoversOwningFrameFromArbitrationEnvelope() {
        // Real bar ends at x=0.125. The ray is microscopically outside the exact cage but within the
        // arbitration-only 1/64 envelope. This models the remaining flicker: the managed ray still
        // hits while vanilla's exact parent clip drops the Frame for one grazing-angle render frame.
        Vec3 from = new Vec3(0.13, 0.5, -1.0);
        Vec3 to = new Vec3(0.13, 0.5, 1.0);
        Vec3 managed = new Vec3(0.13, 0.5, 0.4);

        assertNull(TWO_PIXEL_BAR.clip(from, to, BlockPos.ZERO),
                "exact 2/16 cage must genuinely miss in this regression setup");
        BlockHitResult recovered = ManagedScaleRaycastSupport.findFrameOcclusionCandidate(
                from, to, BlockPos.ZERO, TWO_PIXEL_BAR, managed);
        assertNotNull(recovered,
                "owning Frame arbitration should recover a near-tangent exact-clip miss");
    }

    @Test
    void arbitrationEnvelopeDoesNotExpandSelectionIntoTheOpening() {
        // x=0.15 is beyond both the real 0.125 bar and its 1/64 arbitration envelope (0.140625).
        // A ray through this opening must remain a genuine mini-content ray.
        Vec3 from = new Vec3(0.15, 0.5, -1.0);
        Vec3 to = new Vec3(0.15, 0.5, 1.0);
        Vec3 managed = new Vec3(0.15, 0.5, 0.4);

        assertNull(ManagedScaleRaycastSupport.findFrameOcclusionCandidate(
                from, to, BlockPos.ZERO, TWO_PIXEL_BAR, managed));
    }

    @Test
    void recoveredFrameCannotStealMiniThatIsActuallyInFront() {
        Vec3 from = new Vec3(0.13, 0.5, -1.0);
        Vec3 to = new Vec3(0.13, 0.5, 1.0);
        Vec3 managed = new Vec3(0.13, 0.5, -0.2);

        assertNull(ManagedScaleRaycastSupport.findFrameOcclusionCandidate(
                from, to, BlockPos.ZERO, TWO_PIXEL_BAR, managed));
    }

    @Test
    void ordinaryMacroBlockDoesNotReceiveFrameBias() {
        double managed = 4.0;
        double ordinaryBlock = managed + 0.001;

        assertFalse(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                ordinaryBlock * ordinaryBlock, managed * managed));
    }

    @Test
    void genuinelyCloserOrdinaryBlockStillWins() {
        double managed = 4.0;
        double ordinaryBlock = managed - 0.001;

        assertTrue(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                ordinaryBlock * ordinaryBlock, managed * managed));
    }

    private static Vec3 pointOnRay(double t) {
        return GRAZING_START.add(GRAZING_END.subtract(GRAZING_START).scale(t));
    }
}
