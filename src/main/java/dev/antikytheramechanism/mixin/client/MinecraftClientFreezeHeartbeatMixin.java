package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ClientFreezeWatchdog;
import dev.antikytheramechanism.client.ClientParticlePerfProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Heartbeats the client freeze watchdog and measures client-tick cost while the perf probe is armed. */
@Mixin(Minecraft.class)
abstract class MinecraftClientFreezeHeartbeatMixin {
    @Unique
    private long antikytheramechanism$perfTickStarted;

    @Inject(method = "tick", at = @At("HEAD"))
    private void antikytheramechanism$clientPerfTickStart(CallbackInfo callback) {
        this.antikytheramechanism$perfTickStarted = ClientParticlePerfProbe.startTiming();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void antikytheramechanism$clientFreezeHeartbeat(CallbackInfo callback) {
        ClientParticlePerfProbe.recordClientTick(this.antikytheramechanism$perfTickStarted);
        this.antikytheramechanism$perfTickStarted = 0L;
        ClientFreezeWatchdog.heartbeat();
    }
}
