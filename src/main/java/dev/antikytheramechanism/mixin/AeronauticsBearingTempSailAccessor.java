package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.create.AeronauticsTempSailBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accesses Aeronautics' temporary sail-strength extension after its higher-priority mixin has added
 * that field to Create's BearingContraption. The config plugin enables this accessor only with Aero.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.BearingContraption", priority = 900, remap = false)
public interface AeronauticsBearingTempSailAccessor extends AeronauticsTempSailBridge {
    @Override
    @Accessor("aeronautics$tempSailStrength")
    float antikytheramechanism$getAeroTempSailStrength();

    @Override
    @Accessor("aeronautics$tempSailStrength")
    void antikytheramechanism$setAeroTempSailStrength(float strength);
}
