package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.HostedMiniFloatingMaterialBridge;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Makes hosted mini floating material part of the host's one native self-lift calculation. */
@Mixin(FloatingBlockController.class)
abstract class FloatingBlockControllerHostedMiniMixin {
    @Shadow @Final private ServerSubLevel subLevel;
    @Shadow @Final private List<FloatingClusterContainer> containers;

    @Inject(method = "needsTicking", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$wakeForHostedMiniMaterials(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && HostedMiniFloatingMaterialBridge.hasContribution(this.subLevel)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
                    ordinal = 0,
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$appendHostedMiniMaterials(
            double partialPhysicsTick,
            double timeStep,
            org.joml.Vector3dc linearVelocity,
            org.joml.Vector3dc angularVelocity,
            org.joml.Vector3d linearImpulse,
            org.joml.Vector3d angularImpulse,
            CallbackInfo ci) {
        this.containers.addAll(HostedMiniFloatingMaterialBridge.contributionFor(this.subLevel));
    }

    @Inject(method = "physicsTick", at = @At("RETURN"))
    private void antikytheramechanism$clearHostedMiniMaterialCache(
            double partialPhysicsTick,
            double timeStep,
            org.joml.Vector3dc linearVelocity,
            org.joml.Vector3dc angularVelocity,
            org.joml.Vector3d linearImpulse,
            org.joml.Vector3d angularImpulse,
            CallbackInfo ci) {
        HostedMiniFloatingMaterialBridge.clear(this.subLevel);
    }
}
