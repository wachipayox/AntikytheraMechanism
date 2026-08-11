package dev.antikytheramechanism.api;

/** Result of the public miniaturization policy lookup. */
public enum MiniaturizationStatus {
    /** The block cannot be placed inside a Mechanism Frame. */
    DENIED,
    /** The block is supported by a mod registration or datapack tag. */
    SUPPORTED,
    /** The block was explicitly enabled by the server/common configuration. */
    USER_ALLOWED
}
