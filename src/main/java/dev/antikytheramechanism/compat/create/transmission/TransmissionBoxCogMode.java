package dev.antikytheramechanism.compat.create.transmission;

public enum TransmissionBoxCogMode {
    EMPTY,
    SMALL,
    LARGE;

    public TransmissionBoxCogMode next() {
        return switch (this) {
            case EMPTY -> SMALL;
            case SMALL -> LARGE;
            case LARGE -> EMPTY;
        };
    }
}
