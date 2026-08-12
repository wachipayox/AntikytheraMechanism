package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ClientFreezeWatchdog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Heartbeats the temporary client freeze watchdog after each completed Minecraft client tick. */
@Mixin(Minecraft.class)
abstract class MinecraftClientFreezeHeartbeatMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void antikytheramechanism$clientFreezeHeartbeat(CallbackInfo callback) {
        ClientFreezeWatchdog.heartbeat();
    }
}
