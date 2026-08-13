package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ClientParticlePerfProbe;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures the rendered part of each client frame only while the temporary perf probe is armed. */
@Mixin(GameRenderer.class)
abstract class GameRendererPerfProbeMixin {
    @Unique
    private long antikytheramechanism$renderStarted;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void antikytheramechanism$renderStart(
            DeltaTracker deltaTracker,
            boolean renderLevel,
            CallbackInfo callback) {
        this.antikytheramechanism$renderStarted = ClientParticlePerfProbe.startTiming();
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void antikytheramechanism$renderEnd(
            DeltaTracker deltaTracker,
            boolean renderLevel,
            CallbackInfo callback) {
        ClientParticlePerfProbe.recordRender(this.antikytheramechanism$renderStarted);
        this.antikytheramechanism$renderStarted = 0L;
    }
}
