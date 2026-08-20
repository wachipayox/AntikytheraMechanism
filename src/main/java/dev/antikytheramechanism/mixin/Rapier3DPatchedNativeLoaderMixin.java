package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.PatchedSableRapierNativeLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Antikythera replace Sable's Rapier native with a binary-compatible diagnostic build before
 * Rapier3D's static initializer loads the stock native. Missing/unsupported patched binaries fall
 * through to Sable's original loader unchanged.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false, priority = 1200)
abstract class Rapier3DPatchedNativeLoaderMixin {
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, require = 1)
    private static void antikytheramechanism$loadPatchedRapierNative(CallbackInfo ci) {
        if (PatchedSableRapierNativeLoader.tryLoadPatchedNative()) {
            ci.cancel();
        }
    }
}
