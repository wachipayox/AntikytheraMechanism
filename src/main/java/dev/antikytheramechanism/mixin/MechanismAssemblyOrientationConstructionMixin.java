package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.AssemblyOrientationConstructionContext;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.UUID;

@Mixin(MechanismAssembly.class)
abstract class MechanismAssemblyOrientationConstructionMixin {
    @Inject(method = "<init>(Ljava/util/UUID;Lnet/minecraft/core/BlockPos;Ljava/util/Collection;)V", at = @At("RETURN"))
    private void antikytheramechanism$inherit(UUID id, BlockPos origin, Collection<BlockPos> frames, CallbackInfo ci) {
        var orientation = AssemblyOrientationConstructionContext.current();
        if (orientation != null) ((MechanismAssembly) (Object) this).setOrientation(orientation);
    }
}
