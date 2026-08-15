package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.HostedMiniPunchBridge;
import dev.ryanhcode.sable.network.packets.tcp.ServerboundPunchSubLevelPacket;
import foundry.veil.api.network.handler.PacketContext;
import org.spongepowered.asm.mixin.Mixin;

/** Re-targets Sable's normal punch packet when the clicked body is a managed mini child. */
@Mixin(value = ServerboundPunchSubLevelPacket.class, remap = false)
abstract class ServerboundPunchSubLevelPacketHostedMiniMixin {
    @WrapMethod(method = "handle")
    private void antikytheramechanism$projectHostedMiniPunch(
            PacketContext context,
            Operation<Void> original) {
        ServerboundPunchSubLevelPacket packet =
                (ServerboundPunchSubLevelPacket) (Object) this;
        if (!HostedMiniPunchBridge.handleIfHostedMini(packet, context)) {
            original.call(context);
        }
    }
}
