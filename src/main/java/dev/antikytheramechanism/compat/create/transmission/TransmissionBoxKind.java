package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.compat.create.KineticPortType;

import java.util.List;

/** Immutable gameplay definition shared by the four transmission box blocks. */
public enum TransmissionBoxKind {
    FOUR_SHAFTS(KineticPortType.SHAFT, KineticPortType.SHAFT, true, false),
    FOUR_SMALL_COGS(KineticPortType.SMALL_COG, KineticPortType.SMALL_COG, false, false),
    TWO_LARGE_COGS(KineticPortType.LARGE_COG, KineticPortType.SMALL_COG, false, true),
    TWO_SMALL_COGS(KineticPortType.SMALL_COG, KineticPortType.LARGE_COG, false, true);

    private static final List<Integer> ALL_QUADRANTS = List.of(0, 1, 2, 3);
    private static final List<Integer> DIAGONAL_A = List.of(0, 3);
    private static final List<Integer> DIAGONAL_B = List.of(1, 2);

    private final KineticPortType servicePortType;
    private final KineticPortType targetPortType;
    private final boolean supportsCovers;
    private final boolean diagonal;

    TransmissionBoxKind(
            KineticPortType servicePortType,
            KineticPortType targetPortType,
            boolean supportsCovers,
            boolean diagonal) {
        this.servicePortType = servicePortType;
        this.targetPortType = targetPortType;
        this.supportsCovers = supportsCovers;
        this.diagonal = diagonal;
        servicePortType.requireTransmissionFactorTo(targetPortType);
        targetPortType.requireTransmissionFactorTo(servicePortType);
    }

    public KineticPortType servicePortType() {
        return servicePortType;
    }

    public KineticPortType targetPortType() {
        return targetPortType;
    }

    public boolean supportsCovers() {
        return supportsCovers;
    }

    public boolean usesDiagonalSelection() {
        return diagonal;
    }

    public List<Integer> activeQuadrants(boolean diagonalB) {
        return diagonal ? (diagonalB ? DIAGONAL_B : DIAGONAL_A) : ALL_QUADRANTS;
    }

    public double effectiveFactor() {
        return servicePortType.requireTransmissionFactorTo(targetPortType);
    }
}
