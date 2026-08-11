package dev.antikytheramechanism.compat.create;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Create-independent description of the kinetic connection exposed by a port.
 *
 * <p>This type only captures the ratios verified for Create 6.0.10. The optional Create adapter
 * remains responsible for validating axes and physical cog geometry before creating a bridge.</p>
 */
public enum KineticPortType {
    SHAFT,
    SMALL_COG,
    LARGE_COG;

    /**
     * Returns the RPM factor applied when this port drives {@code target}.
     * An empty result means the pair is not part of the supported compatibility table.
     */
    public OptionalDouble transmissionFactorTo(KineticPortType target) {
        Objects.requireNonNull(target, "target");
        if (this == SHAFT && target == SHAFT) {
            return OptionalDouble.of(1.0D);
        }
        if (this == SMALL_COG && target == SMALL_COG) {
            return OptionalDouble.of(-1.0D);
        }
        if (this == LARGE_COG && target == SMALL_COG) {
            return OptionalDouble.of(-2.0D);
        }
        if (this == SMALL_COG && target == LARGE_COG) {
            return OptionalDouble.of(-0.5D);
        }
        return OptionalDouble.empty();
    }

    public boolean canTransmitTo(KineticPortType target) {
        return transmissionFactorTo(target).isPresent();
    }

    /**
     * Returns the supported factor or rejects the pair before a bridge can become active.
     */
    public double requireTransmissionFactorTo(KineticPortType target) {
        return transmissionFactorTo(target).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported kinetic port pair: " + this + " -> " + target));
    }
}
