package dev.antikytheramechanism.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScaleRaycastSupportTest {
    private static final VoxelShape TWO_PIXEL_BAR = Block.box(0, 0, 0, 2, 16, 2);
    private static final Vec3 GRAZING_START = new Vec3(-0.1, 0.5, -1.0);
    private static final Vec3 GRAZING_END = new Vec3(0.1, 0.5, 1.0);
    private static final Vec3 EXACT_BAR_ENTRY = pointOnRay(0.5);

    @Test
    void grazingAngleMiniInsidePhysicalBarEnvelopeKeepsFrameStable() {
        // The real bar ends at z=2/16=0.125. At this grazing angle the managed hit can be more than
        // 1/64 away ALONG THE RAY while still only a tiny physical distance beyond that surface.
        // The old fixed along-ray tolerance flickered here; the bar-volume rule must remain stable.
        Vec3 managed = pointOnRay(0.568); // z=0.136, inside the 1/64 arbitration envelope.

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
        Vec3 managed = pointOnRay(0.495); // slightly before the exact t=0.5 entry, inside envelope.

        assertTrue(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                GRAZING_START,
                GRAZING_END,
                BlockPos.ZERO,
                TWO_PIXEL_BAR,
                EXACT_BAR_ENTRY,
                managed));
    }

    @Test
    void miniClearlyPastTheBarIsNotStolen() {
        Vec3 managed = pointOnRay(0.60); // z=0.20, beyond the real bar and its tiny envelope.

        assertFalse(ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
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
