package dev.antikytheramechanism.compat.create;

/** Implemented only when Aeronautics is present, so Create-only code never links Aero classes. */
public interface MiniSailPropellerBridge {
    void antikytheramechanism$refreshMiniSails(DynamicMiniSailSnapshot snapshot);

    double antikytheramechanism$getEffectiveSailPower();

    double antikytheramechanism$getMinimumSailPower();
}
