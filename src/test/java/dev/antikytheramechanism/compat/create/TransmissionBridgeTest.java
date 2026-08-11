package dev.antikytheramechanism.compat.create;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.antikytheramechanism.compat.create.TransmissionBridge.Mode.BALANCED_NATIVE_SOURCES;
import static dev.antikytheramechanism.compat.create.TransmissionBridge.Mode.IDLE;
import static dev.antikytheramechanism.compat.create.TransmissionBridge.Mode.MINI_DRIVES_PARENT;
import static dev.antikytheramechanism.compat.create.TransmissionBridge.Mode.PARENT_DRIVES_MINI;
import static dev.antikytheramechanism.compat.create.TransmissionBridge.Mode.REJECTED_INCOMPATIBLE_NATIVE_SOURCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionBridgeTest {
    @Test
    void bridgeRejectsUnsupportedPortPairsAtConstruction() {
        MiniKineticEndpoint mini = endpoint(KineticPortType.SMALL_COG);

        assertThrows(IllegalArgumentException.class,
                () -> new TransmissionBridge(KineticPortType.SHAFT, mini));
    }

    @Test
    void idleNetworksDoNotReceiveInjectedSpeed() {
        TransmissionBridge bridge = bridge(KineticPortType.SHAFT, KineticPortType.SHAFT);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.inactive(),
                TransmissionBridge.SideObservation.inactive());

        assertState(state, IDLE, 0.0D, 0.0D);
    }

    @Test
    void parentLargeCogDrivesMiniSmallCogAtNegativeDoubleSpeed() {
        TransmissionBridge bridge = bridge(KineticPortType.LARGE_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.nativeSource(24.0D, 24.0D),
                TransmissionBridge.SideObservation.inactive());

        assertState(state, PARENT_DRIVES_MINI, 0.0D, -48.0D);
    }

    @Test
    void miniSmallCogDrivesParentLargeCogAtNegativeHalfSpeed() {
        TransmissionBridge bridge = bridge(KineticPortType.LARGE_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.inactive(),
                TransmissionBridge.SideObservation.nativeSource(32.0D, 32.0D));

        assertState(state, MINI_DRIVES_PARENT, -16.0D, 0.0D);
    }

    @Test
    void effectiveSpeedRatherThanTheoreticalSpeedIsInjected() {
        TransmissionBridge bridge = bridge(KineticPortType.SMALL_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.nativeSource(64.0D, 0.0D),
                TransmissionBridge.SideObservation.inactive());

        assertState(state, PARENT_DRIVES_MINI, 0.0D, 0.0D);
    }

    @Test
    void compatibleNativeSourcesRemainBalancedWithoutInjection() {
        TransmissionBridge bridge = bridge(KineticPortType.LARGE_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.nativeSource(16.0D, 16.0D),
                TransmissionBridge.SideObservation.nativeSource(-32.0D, -32.0D));

        assertState(state, BALANCED_NATIVE_SOURCES, 0.0D, 0.0D);
    }

    @Test
    void incompatibleNativeSourcesAreRejectedInsteadOfSelectingADriver() {
        TransmissionBridge bridge = bridge(KineticPortType.LARGE_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.nativeSource(16.0D, 16.0D),
                TransmissionBridge.SideObservation.nativeSource(32.0D, 32.0D));

        assertState(state, REJECTED_INCOMPATIBLE_NATIVE_SOURCES, 0.0D, 0.0D);
        assertTrue(state.isRejected());
    }

    @Test
    void differingEffectiveSpeedsAlsoRejectDualSources() {
        TransmissionBridge bridge = bridge(KineticPortType.SMALL_COG, KineticPortType.SMALL_COG);

        TransmissionBridge.State state = bridge.resolve(
                TransmissionBridge.SideObservation.nativeSource(20.0D, 0.0D),
                TransmissionBridge.SideObservation.nativeSource(-20.0D, -20.0D));

        assertState(state, REJECTED_INCOMPATIBLE_NATIVE_SOURCES, 0.0D, 0.0D);
    }

    @Test
    void nonFiniteObservationsCannotEnterTheBridgeStateMachine() {
        assertThrows(IllegalArgumentException.class,
                () -> TransmissionBridge.SideObservation.nativeSource(Double.NaN, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> TransmissionBridge.SideObservation.nativeSource(0.0D, Double.POSITIVE_INFINITY));
    }

    @Test
    void publicStateConstructorPreservesIsolationInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransmissionBridge.State(IDLE, 1.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new TransmissionBridge.State(PARENT_DRIVES_MINI, 1.0D, 2.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new TransmissionBridge.State(MINI_DRIVES_PARENT, 2.0D, 1.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new TransmissionBridge.State(REJECTED_INCOMPATIBLE_NATIVE_SOURCES, 0.0D, 1.0D));
    }

    private static TransmissionBridge bridge(KineticPortType parent, KineticPortType mini) {
        return new TransmissionBridge(parent, endpoint(mini));
    }

    private static MiniKineticEndpoint endpoint(KineticPortType type) {
        return new MiniKineticEndpoint(UUID.randomUUID(), 3, -2, 5, type);
    }

    private static void assertState(
            TransmissionBridge.State state,
            TransmissionBridge.Mode mode,
            double injectedParentRpm,
            double injectedMiniRpm) {
        assertEquals(mode, state.mode());
        assertEquals(injectedParentRpm, state.injectedParentRpm());
        assertEquals(injectedMiniRpm, state.injectedMiniRpm());
    }
}
