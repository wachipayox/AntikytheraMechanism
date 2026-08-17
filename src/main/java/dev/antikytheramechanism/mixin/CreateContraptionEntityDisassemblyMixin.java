package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.compat.create.CreateForcedDisassemblyContext;
import dev.antikytheramechanism.compat.create.CreateFrameDisassemblyPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Snaps a forced controller-loss disassembly before Create sends or applies its final transform. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity", remap = false)
abstract class CreateContraptionEntityDisassemblyMixin {
    @Shadow protected Contraption contraption;

    @ModifyExpressionValue(
            method = "disassemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;makeStructureTransform()Lcom/simibubi/create/content/contraptions/StructureTransform;"),
            remap = false)
    private StructureTransform antikytheramechanism$snapForcedFrameAssemblyUpright(StructureTransform original) {
        if (!CreateForcedDisassemblyContext.isForced()) {
            return original;
        }
        return CreateFrameDisassemblyPolicy.nearestForcedUprightTransform(contraption, original);
    }
}
