package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets Sable maintain a managed child's floating-material bookkeeping without allowing that
 * pose-driven child to accelerate independently. Hosted effects are contributed separately to the
 * physical host's own FloatingBlockController before Sable computes prevent-self-lift.
 */
@Mixin(ServerSubLevel.class)
abstract class ServerSubLevelMiniPhysicsMixin {
    @WrapOperation(
            method = "prePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockController;physicsTick(DDLorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;Lorg/joml/Vector3d;)V"))
    private void antikytheramechanism$discardManagedChildFloatingImpulse(
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

        // The child is semantically attached to its Frame and is re-driven after every physics
        // substep. Its own floating contribution must therefore never turn it into a free body.
        linearImpulse.set(linearBefore);
        angularImpulse.set(angularBefore);
    }
}
