package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes Create's synchronized stationary controller position to client-only selection bridges. */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
abstract class ControlledContraptionEntityClientAccessMixin
        implements CreateContraptionClientAccess.ControllerCarrier {
    @Shadow protected BlockPos controllerPos;

    @Override
    public @Nullable BlockPos getAntikytheraControllerPos() {
        return controllerPos == null ? null : controllerPos.immutable();
    }
}
