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
        // Once the ray has crossed a real Frame bar, a deeper mini impact cannot become visible just
        // because its hit point lies beyond the rear face of that thin bar. The old finite-interval
        // arbitration returned false here and caused the remaining angle-dependent flicker.
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
