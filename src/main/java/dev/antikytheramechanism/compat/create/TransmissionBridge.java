package dev.antikytheramechanism.compat.create;

import java.util.Objects;

/**
 * Immutable, Create-independent definition of a parent-to-mini kinetic bridge.
 *
 * <p>The optional runtime adapter supplies observations that exclude speed previously injected by
 * this bridge. Resolution never chooses arbitrarily between two native sources: compatible sources
 * are reported as balanced and incompatible sources are rejected for the adapter to isolate.</p>
 */
public record TransmissionBridge(KineticPortType parentPortType, MiniKineticEndpoint miniEndpoint) {
    private static final double COMPARISON_EPSILON = 1.0E-6D;

    public TransmissionBridge {
        Objects.requireNonNull(parentPortType, "parentPortType");
        Objects.requireNonNull(miniEndpoint, "miniEndpoint");

        // Validate both directions now so a persisted bridge can never contain a one-way-only pair.
        parentPortType.requireTransmissionFactorTo(miniEndpoint.portType());
        miniEndpoint.portType().requireTransmissionFactorTo(parentPortType);
    }

    /** RPM factor applied when the parent endpoint drives the mini endpoint. */
    public double parentToMiniFactor() {
        return parentPortType.requireTransmissionFactorTo(miniEndpoint.portType());
    }

    /** RPM factor applied when the mini endpoint drives the parent endpoint. */
    public double miniToParentFactor() {
        return miniEndpoint.portType().requireTransmissionFactorTo(parentPortType);
    }

    /**
     * Resolves which side, if any, may be injected during this update.
     *
     * <p>Theoretical RPM determines ratio compatibility. Effective RPM is checked separately and is
     * the only speed emitted for injection, preventing an overstressed side from feeding a non-zero
     * theoretical speed into the other level.</p>
     */
    public State resolve(SideObservation parent, SideObservation mini) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(mini, "mini");

        if (parent.hasNativeSource() && mini.hasNativeSource()) {
            double factor = parentToMiniFactor();
            boolean theoreticalCompatible = approximatelyEqual(
                    parent.theoreticalRpm() * factor, mini.theoreticalRpm());
            boolean effectiveCompatible = approximatelyEqual(
                    parent.effectiveRpm() * factor, mini.effectiveRpm());
            if (theoreticalCompatible && effectiveCompatible) {
                return State.balanced();
            }
            return State.rejectedSourceConflict();
        }

        if (parent.hasNativeSource()) {
            return State.parentDrives(normalizeZero(parent.effectiveRpm() * parentToMiniFactor()));
        }
        if (mini.hasNativeSource()) {
            return State.miniDrives(normalizeZero(mini.effectiveRpm() * miniToParentFactor()));
        }
        return State.idle();
    }

    private static boolean approximatelyEqual(double expected, double actual) {
        double scale = Math.max(1.0D, Math.max(Math.abs(expected), Math.abs(actual)));
        return Math.abs(expected - actual) <= COMPARISON_EPSILON * scale;
    }

    private static double normalizeZero(double value) {
        return value == 0.0D ? 0.0D : value;
    }

    /** Snapshot of one local Create network, produced by the optional adapter. */
    public record SideObservation(boolean hasNativeSource, double theoreticalRpm, double effectiveRpm) {
        public SideObservation {
            if (!Double.isFinite(theoreticalRpm) || !Double.isFinite(effectiveRpm)) {
                throw new IllegalArgumentException("Kinetic RPM observations must be finite");
            }
        }

        public static SideObservation inactive() {
            return new SideObservation(false, 0.0D, 0.0D);
        }

        public static SideObservation nativeSource(double theoreticalRpm, double effectiveRpm) {
            return new SideObservation(true, theoreticalRpm, effectiveRpm);
        }
    }

    public enum Mode {
        IDLE,
        PARENT_DRIVES_MINI,
        MINI_DRIVES_PARENT,
        BALANCED_NATIVE_SOURCES,
        REJECTED_INCOMPATIBLE_NATIVE_SOURCES
    }

    /**
     * Pure resolution result. At most one injected RPM is non-zero; both are zero for balanced,
     * idle and rejected states.
     */
    public record State(Mode mode, double injectedParentRpm, double injectedMiniRpm) {
        public State {
            Objects.requireNonNull(mode, "mode");
            if (!Double.isFinite(injectedParentRpm) || !Double.isFinite(injectedMiniRpm)) {
                throw new IllegalArgumentException("Injected RPM must be finite");
            }
            switch (mode) {
                case IDLE, BALANCED_NATIVE_SOURCES, REJECTED_INCOMPATIBLE_NATIVE_SOURCES -> {
                    if (injectedParentRpm != 0.0D || injectedMiniRpm != 0.0D) {
                        throw new IllegalArgumentException(mode + " must not inject either endpoint");
                    }
                }
                case PARENT_DRIVES_MINI -> {
                    if (injectedParentRpm != 0.0D) {
                        throw new IllegalArgumentException("A parent-driven bridge cannot inject its parent");
                    }
                }
                case MINI_DRIVES_PARENT -> {
                    if (injectedMiniRpm != 0.0D) {
                        throw new IllegalArgumentException("A mini-driven bridge cannot inject its mini endpoint");
                    }
                }
            }
        }

        public static State idle() {
            return new State(Mode.IDLE, 0.0D, 0.0D);
        }

        public static State parentDrives(double injectedMiniRpm) {
            return new State(Mode.PARENT_DRIVES_MINI, 0.0D, injectedMiniRpm);
        }

        public static State miniDrives(double injectedParentRpm) {
            return new State(Mode.MINI_DRIVES_PARENT, injectedParentRpm, 0.0D);
        }

        public static State balanced() {
            return new State(Mode.BALANCED_NATIVE_SOURCES, 0.0D, 0.0D);
        }

        public static State rejectedSourceConflict() {
            return new State(Mode.REJECTED_INCOMPATIBLE_NATIVE_SOURCES, 0.0D, 0.0D);
        }

        public boolean isRejected() {
            return mode == Mode.REJECTED_INCOMPATIBLE_NATIVE_SOURCES;
        }
    }
}
