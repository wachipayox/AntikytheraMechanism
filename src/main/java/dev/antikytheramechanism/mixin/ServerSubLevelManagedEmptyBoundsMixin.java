package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.ManagedSubLevelBounds;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame ownership, not fake plot content or fake bounds, keeps an empty managed SubLevel alive. */
@Mixin(value = ServerSubLevel.class, priority = 2000)
abstract class ServerSubLevelManagedEmptyBoundsMixin {
    @Inject(method = "onPlotBoundsChanged", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$preserveManagedEmptyAssembly(CallbackInfo callback) {
        if (ManagedSubLevelBounds.preserveIfEmpty((ServerSubLevel) (Object) this)) {
            callback.cancel();
        }
    }
}
