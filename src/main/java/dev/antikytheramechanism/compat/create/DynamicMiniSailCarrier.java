package dev.antikytheramechanism.compat.create;

/** Transient extension mixed into Create BearingContraption; never mutates its captured block map. */
public interface DynamicMiniSailCarrier {
    DynamicMiniSailSnapshot antikytheramechanism$getMiniSails();

    void antikytheramechanism$setMiniSails(DynamicMiniSailSnapshot snapshot);
}
