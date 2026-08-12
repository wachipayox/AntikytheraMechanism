package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.ManagedSubLevelMassPolicy;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensures Rapier never receives an Antikythera rigid body without a center of mass. */
@Mixin(value = ServerSubLevel.class, priority = 2000)
abstract class ServerSubLevelStructuralMassMixin {
    @Inject(method = "buildMassTracker", at = @At("RETURN"))
    private void antikytheramechanism$addStructuralMass(CallbackInfo ci) {
        ManagedSubLevelMassPolicy.applyStructuralMass((ServerSubLevel) (Object) this);
    }
}
