package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionDisassemblyPacket;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import dev.antikytheramechanism.client.CreateContraptionDisassemblySnap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
abstract class CreateContraptionEntityClientAccessMixin implements CreateContraptionClientAccess.EntityCarrier {
    @Shadow protected Contraption contraption;

    @Override
    public Object getAntikytheraContraption() {
        return contraption;
    }

    @Inject(method = "handleDisassemblyPacket", at = @At("HEAD"))
    private static void antikytheramechanism$captureDisassemblySnap(
            ContraptionDisassemblyPacket packet,
            CallbackInfo callback) {
        CreateContraptionDisassemblySnap.capture(packet.entityId(), packet.transform());
    }
}
