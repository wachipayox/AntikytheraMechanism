package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.AssemblyOrientationConstructionContext;
import dev.antikytheramechanism.assembly.AssemblyOrientationMath;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblySplitOrientationMixin {
    @Unique private MechanismAssembly antikytheramechanism$splitSource;

    @Inject(method = "splitDisconnectedAssembly", at = @At("HEAD"))
    private void antikytheramechanism$begin(ServerLevel level, MechanismAssembly source, CallbackInfo ci) {
        antikytheramechanism$splitSource = source;
        AssemblyOrientationConstructionContext.begin(source.orientation());
    }

    @Inject(method = "splitDisconnectedAssembly", at = @At("RETURN"))
    private void antikytheramechanism$end(ServerLevel level, MechanismAssembly source, CallbackInfo ci) {
        antikytheramechanism$splitSource = null;
        AssemblyOrientationConstructionContext.end();
    }

    @Redirect(method = "splitDisconnectedAssembly",
            at = @At(value = "INVOKE", target = "Ldev/antikytheramechanism/assembly/AssemblyPose;rebased(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ldev/antikytheramechanism/assembly/AssemblyPose;"))
    private AssemblyPose antikytheramechanism$logicalRebase(AssemblyPose pose, BlockPos oldOrigin, BlockPos newOrigin) {
        MechanismAssembly source = antikytheramechanism$splitSource;
        return source == null ? pose.rebased(oldOrigin, newOrigin)
                : AssemblyOrientationMath.rebaseLogical(pose, source.logicalFrameOffset(newOrigin));
    }
}
