package dev.antikytheramechanism.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScaleRaycastSupportTest {
    @Test
    void frameWinsWhenOnlySlightlyBehindCoplanarMiniSurface() {
        double managed = 4.0;
        double frame = managed + 0.01;

        assertTrue(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                true, frame * frame, managed * managed));
    }

    @Test
    void frameDoesNotStealClearlyDeeperMiniHit() {
        double managed = 4.0;
        double frame = managed + 0.02;

        assertFalse(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                true, frame * frame, managed * managed));
    }

    @Test
    void ordinaryMacroBlockDoesNotReceiveFrameBias() {
        double managed = 4.0;
        double ordinaryBlock = managed + 0.001;

        assertFalse(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                false, ordinaryBlock * ordinaryBlock, managed * managed));
    }

    @Test
    void genuinelyCloserOrdinaryBlockStillWins() {
        double managed = 4.0;
        double ordinaryBlock = managed - 0.001;

        assertTrue(ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                false, ordinaryBlock * ordinaryBlock, managed * managed));
    }
}
