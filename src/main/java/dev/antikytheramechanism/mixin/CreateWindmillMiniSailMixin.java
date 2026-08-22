package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.compat.create.DynamicMiniSailCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Makes windmill RPM consume effective area power while preserving Create's frozen integer field. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity", remap = false)
abstract class CreateWindmillMiniSailMixin {
    @ModifyExpressionValue(
            method = "getGeneratedSpeed",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/bearing/BearingContraption;getSailBlocks()I"))
    private int antikytheramechanism$useEffectiveSailPower(int nativeSails) {
        com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity self =
                (com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity) (Object) this;
        ControlledContraptionEntity moved = self.getMovedContraption();
        if (moved == null || !(moved.getContraption() instanceof DynamicMiniSailCarrier carrier)) {
            return nativeSails;
        }
        return (int) Math.floor(nativeSails
                + carrier.antikytheramechanism$getMiniSails().miniSailPower()
                + 1.0E-9);
    }
}
