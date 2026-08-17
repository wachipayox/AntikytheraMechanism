package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.DeferredFrameRemovalLifecycle;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs queued direct removals only after the outer Sable/vanilla world write has completed. */
@Mixin(MechanismAssemblyManager.class)
public abstract class MechanismAssemblyManagerDeferredRemovalMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void antikytheramechanism$processDeferredFrameRemovals(
            ServerLevel level,
            CallbackInfo callbackInfo) {
        DeferredFrameRemovalLifecycle.process(level);
    }
}
