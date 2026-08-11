package dev.antikytheramechanism.compat.create;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KineticPortTypeTest {
    @Test
    void verifiedFactorsIncludeTheirCreateSigns() {
        assertEquals(1.0D, KineticPortType.SHAFT.requireTransmissionFactorTo(KineticPortType.SHAFT));
        assertEquals(-1.0D, KineticPortType.SMALL_COG.requireTransmissionFactorTo(KineticPortType.SMALL_COG));
        assertEquals(-2.0D, KineticPortType.LARGE_COG.requireTransmissionFactorTo(KineticPortType.SMALL_COG));
        assertEquals(-0.5D, KineticPortType.SMALL_COG.requireTransmissionFactorTo(KineticPortType.LARGE_COG));
    }

    @Test
    void directionalGearFactorsAreReciprocal() {
        double largeToSmall = KineticPortType.LARGE_COG
                .requireTransmissionFactorTo(KineticPortType.SMALL_COG);
        double smallToLarge = KineticPortType.SMALL_COG
                .requireTransmissionFactorTo(KineticPortType.LARGE_COG);

        assertEquals(1.0D, largeToSmall * smallToLarge);
    }

    @Test
    void undocumentedOrMixedPairsAreRejected() {
        assertEquals(OptionalDouble.empty(),
                KineticPortType.LARGE_COG.transmissionFactorTo(KineticPortType.LARGE_COG));
        assertFalse(KineticPortType.SHAFT.canTransmitTo(KineticPortType.SMALL_COG));
        assertThrows(IllegalArgumentException.class,
                () -> KineticPortType.SHAFT.requireTransmissionFactorTo(KineticPortType.LARGE_COG));
    }
}
