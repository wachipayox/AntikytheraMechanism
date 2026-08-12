package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.ManagedClientSubLevelTracking;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import foundry.veil.api.network.handler.PacketContext;
import org.spongepowered.asm.mixin.Mixin;

/** Ensures the initial empty-bounds update can identify Antikythera before Sable assigns its name. */
@Mixin(value = ClientboundStartTrackingSubLevelPacket.class, remap = false)
abstract class ClientboundStartTrackingSubLevelManagedContextMixin {
    @WrapMethod(method = "handle")
    private void antikytheramechanism$managedTrackingContext(PacketContext context, Operation<Void> original) {
        ClientboundStartTrackingSubLevelPacket packet =
                (ClientboundStartTrackingSubLevelPacket) (Object) this;
        String name = packet.name();
        if (name == null || !name.startsWith("antikythera-")) {
            original.call(context);
            return;
        }

        ManagedClientSubLevelTracking.duringManagedTracking(() -> original.call(context));
    }
}
