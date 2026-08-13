package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
abstract class CreateContraptionEntityClientAccessMixin implements CreateContraptionClientAccess.EntityCarrier {
    @Shadow protected Contraption contraption;

    @Override
    public Object getAntikytheraContraption() {
        return contraption;
    }
}
