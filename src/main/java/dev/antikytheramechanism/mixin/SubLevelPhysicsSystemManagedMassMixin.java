package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antikytheramechanism.sublevel.ManagedSubLevelMassPolicy;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable also performs an immediate zero-mass removal after a plot block changes. Frame ownership is
 * the lifetime authority for Antikythera, so that generic cleanup must not delete managed SubLevels.
 */
@Mixin(value = SubLevelPhysicsSystem.class, priority = 2000)
abstract class SubLevelPhysicsSystemManagedMassMixin {
    @ModifyExpressionValue(
            method = "updateMassDataFromBlockChange",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/mass/MassTracker;isInvalid()Z"))
    private boolean antikytheramechanism$keepFrameOwnedMasslessSubLevel(
            boolean original,
            @Local ServerSubLevel subLevel) {
        return original && !ManagedSubLevelMassPolicy.mayRemainMassless(subLevel);
    }
}
