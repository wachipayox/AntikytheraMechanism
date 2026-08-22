package dev.antikytheramechanism.client;

/** Pure timing checks kept package-local so the pulse curve can be validated without client world state. */
final class HiddenFramePlacementRejectionPulseTests {
    private HiddenFramePlacementRejectionPulseTests() {
    }

    static void assertCurve() {
        long duration = HiddenFramePlacementRejectionPulse.DURATION_NANOS;
        check(HiddenFramePlacementRejectionPulse.pulseAlpha(0L) == 0.0f,
                "pulse must start transparent");
        check(HiddenFramePlacementRejectionPulse.pulseAlpha(duration / 2L) > 0.999f,
                "pulse must peak near the midpoint");
        check(HiddenFramePlacementRejectionPulse.pulseAlpha(duration) == 0.0f,
                "pulse must expire after one second");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
