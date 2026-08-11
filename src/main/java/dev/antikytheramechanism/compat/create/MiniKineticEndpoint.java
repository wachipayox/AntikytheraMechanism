package dev.antikytheramechanism.compat.create;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable, immutable identity of a kinetic endpoint inside an assembly's mini coordinate space.
 * Coordinates are local mini-block coordinates and therefore do not change when the assembly moves.
 */
public record MiniKineticEndpoint(
        UUID assemblyId,
        int localX,
        int localY,
        int localZ,
        KineticPortType portType) {

    public MiniKineticEndpoint {
        Objects.requireNonNull(assemblyId, "assemblyId");
        Objects.requireNonNull(portType, "portType");
    }
}
