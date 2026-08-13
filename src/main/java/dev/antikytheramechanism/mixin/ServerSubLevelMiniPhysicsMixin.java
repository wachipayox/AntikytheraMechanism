package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.api.physics.MiniPhysicsEffectRegistry;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniPhysicsBuiltins;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerSubLevel.class)
abstract class ServerSubLevelMiniPhysicsMixin {
    @WrapOperation(
            method = "prePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockController;physicsTick(DDLorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;Lorg/joml/Vector3d;)V"))
    private void antikytheramechanism$transferFloatingMaterialEffect(
            FloatingBlockController controller,
            double partialPhysicsTick,
            double timeStep,
            Vector3dc linearVelocity,
            Vector3dc angularVelocity,
            Vector3d linearImpulse,
            Vector3d angularImpulse,
            Operation<Void> original) {
        ServerSubLevel child = (ServerSubLevel) (Object) this;
        if (MechanismSubLevelService.getOwnerAssemblyId(child) == null) {
            original.call(
                    controller,
                    partialPhysicsTick,
                    timeStep,
                    linearVelocity,
                    angularVelocity,
                    linearImpulse,
                    angularImpulse);
            return;
        }

        Vector3d linearBefore = new Vector3d(linearImpulse);
        Vector3d angularBefore = new Vector3d(angularImpulse);
        original.call(
                controller,
                partialPhysicsTick,
                timeStep,
                linearVelocity,
                angularVelocity,
                linearImpulse,
                angularImpulse);

        Vector3d floatingLinear = new Vector3d(linearImpulse).sub(linearBefore);
        Vector3d floatingAngular = new Vector3d(angularImpulse).sub(angularBefore);

        /*
         * Managed children are pose-driven by Antikythera rather than free physical bodies. Let Sable
         * compute its native floating-material effect, but remove that exact contribution from the
         * child's accumulator before offering it to the explicit host-transfer API. Other impulses
         * accumulated before the floating controller remain untouched.
         */
        linearImpulse.set(linearBefore);
        angularImpulse.set(angularBefore);

        if (floatingLinear.lengthSquared() <= 1.0E-20 && floatingAngular.lengthSquared() <= 1.0E-20) {
            return;
        }

        MiniPhysicsEffectRegistry.transfer(
                MiniPhysicsBuiltins.SABLE_FLOATING_MATERIAL,
                child,
                floatingLinear,
                floatingAngular);
    }
}
