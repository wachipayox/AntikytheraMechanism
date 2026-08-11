package dev.antikytheramechanism.compat.create.transmission;

/** Persistent diagnostic state; only ACTIVE exposes remote propagation edges. */
public enum TransmissionLinkState {
    UNBOUND,
    INSTALLING_LOCAL,
    ACTIVE,
    SUSPENDED,
    CONFLICT,
    RECOVERY_REQUIRED
}
