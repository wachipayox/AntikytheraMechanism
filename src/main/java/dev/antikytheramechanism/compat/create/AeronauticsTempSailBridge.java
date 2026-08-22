package dev.antikytheramechanism.compat.create;

/**
 * Optional bridge implemented on Create's BearingContraption only when Aeronautics is present.
 * Keeping this contract outside the mixin package lets Create-only runtime code test for support
 * without classloading a mixin definition.
 */
public interface AeronauticsTempSailBridge {
    float antikytheramechanism$getAeroTempSailStrength();

    void antikytheramechanism$setAeroTempSailStrength(float strength);
}
