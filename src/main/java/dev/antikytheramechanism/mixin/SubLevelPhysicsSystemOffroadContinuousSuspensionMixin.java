package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadContinuousSuspensionPrototype;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Splits a normal Sable physics substep into internal Rapier microsteps only when the Offroad
 * continuous-suspension prototype captured suspension momentum for that substep.
 *
 * <p>The outer Sable cadence, actor cadence, gravity duration and total simulated time are unchanged.
 * Only {@link PhysicsPipeline#physicsTick(double)} is subdivided so the captured suspension momentum
 * can be delivered incrementally between contact solves.</p>
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
    private void antikytheramechanism$microstepOffroadSuspension(
            PhysicsPipeline pipeline,
            double timeStep) {
        OffroadContinuousSuspensionPrototype.physicsTick(
                (SubLevelPhysicsSystem) (Object) this,
                pipeline,
                timeStep);
    }
}
