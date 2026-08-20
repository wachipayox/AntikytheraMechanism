package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadNativeContinuousForcePrototype;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes Sable's Rapier step through the active Offroad force-delivery experiment.
 *
 * <p>The native continuous-force prototype gets first chance to surround one ordinary Rapier step with
 * a transient external force. When no native force is pending it delegates to the existing microstep
 * prototype, preserving the known-good diagnostic and its runtime toggle.</p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
abstract class SubLevelPhysicsSystemOffroadContinuousSuspensionMixin {
    @Redirect(
            method = "tickPipelinePhysics",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;physicsTick(D)V"),
            require = 1,
            remap = false)
    private void antikytheramechanism$routeOffroadForceDelivery(
            PhysicsPipeline pipeline,
            double timeStep) {
        OffroadNativeContinuousForcePrototype.physicsTick(
                (SubLevelPhysicsSystem) (Object) this,
                pipeline,
                timeStep);
    }
}
