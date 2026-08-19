package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.assembly.ContraptionSourceRelease;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands a Create-vacated source coordinate out of the moving assembly's live frame index. */
@Mixin(MechanismFrameBlock.class)
public abstract class MechanismFrameVacatedSourceMixin {
    @WrapOperation(
            method = "onRemove",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/antikytheramechanism/assembly/MechanismAssemblyManager;isPhysicalRelocationTransition(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean antikytheramechanism$releaseCreateSourceBeforeRemovalDecision(
            MechanismAssemblyManager manager,
            BlockPos position,
            Operation<Boolean> original) {
        return ContraptionSourceRelease.release(manager, position) || original.call(manager, position);
    }
}
