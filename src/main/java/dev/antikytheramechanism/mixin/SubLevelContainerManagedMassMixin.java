package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antikytheramechanism.sublevel.ManagedSubLevelMassPolicy;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents Sable's generic zero-mass cleanup from deleting frame-owned mechanism SubLevels. */
@Mixin(value = SubLevelContainer.class, priority = 2000)
abstract class SubLevelContainerManagedMassMixin {
    @ModifyExpressionValue(
            method = "processSubLevelRemovals",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/mass/MassData;isInvalid()Z"))
    private boolean antikytheramechanism$keepFrameOwnedMasslessSubLevel(
            boolean original,
            @Local ServerSubLevel subLevel) {
        return original && !ManagedSubLevelMassPolicy.mayRemainMassless(subLevel);
    }
}
