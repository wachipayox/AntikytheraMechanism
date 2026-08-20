package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadContinuousSuspensionPrototype;
import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics;
import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics.Term;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.util.Mth;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary diagnostic hooks for Create Offroad's Wheel Mount force solver.
 *
 * <p>Each force component can be disabled independently for a physical Sable sub-level at runtime
 * through {@link OffroadWheelDiagnostics}. Every redirect is strict: the Offroad implementation under
 * test must contain exactly the expected operation or Mixin fails loudly instead of silently making a
 * diagnostic command ineffective.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
abstract class OffroadWheelMountExperimentalNoForcesMixin {
    @Unique
    private ServerSubLevel antikytheramechanism$currentSubLevel;

    /** Spring+damping impulse before Offroad adds longitudinal/lateral tire forces. */
    @Unique
    private final Vector3d antikytheramechanism$suspensionImpulse = new Vector3d();

    /**
     * Multiplier used only by wheel_updates_per_tick. When a 30-substep Sable tick is deliberately
     * reduced to two Wheel Mount evaluations, each retained wheel impulse is multiplied by 15 so the
     * integrated support/drive impulse across the Minecraft tick remains approximately unchanged.
     */
    @Unique
    private double antikytheramechanism$wheelImpulseScale = 1.0;

    /**
     * Optionally decouple Wheel Mount evaluation cadence from Rapier's substep cadence. This lets us
     * run e.g. 30 Rapier steps per tick while preserving only two evenly-spaced wheel evaluations.
     *
     * <p>Updates are retained at the beginning of each coarse interval. For 30 Rapier substeps and two
     * requested wheel updates this means substeps 0 and 15, matching the 25 ms cadence of a normal
     * two-substep tick while still giving Rapier fifteen contact solves between wheel impulses.</p>
     */
    @Inject(method = "sable$physicsTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void antikytheramechanism$captureDiagnosticSubLevel(
            ServerSubLevel subLevel,
            RigidBodyHandle handle,
            double timeStep,
            CallbackInfo ci) {
        this.antikytheramechanism$currentSubLevel = subLevel;
        this.antikytheramechanism$suspensionImpulse.zero();
        this.antikytheramechanism$wheelImpulseScale = 1.0;

        int requestedUpdates = OffroadWheelDiagnostics.wheelUpdatesPerTick(subLevel);
        if (requestedUpdates <= 0) {
            return;
        }

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        int totalSubsteps = physicsSystem.getConfig().substepsPerTick;
        if (totalSubsteps <= 0 || requestedUpdates >= totalSubsteps) {
            return;
        }

        int currentSubstep = (int) Math.round(physicsSystem.getPartialPhysicsTick() * totalSubsteps) - 1;
        currentSubstep = Math.max(0, Math.min(totalSubsteps - 1, currentSubstep));

        int currentInterval = currentSubstep * requestedUpdates / totalSubsteps;
        int previousInterval = currentSubstep == 0
                ? -1
                : (currentSubstep - 1) * requestedUpdates / totalSubsteps;
        if (currentInterval == previousInterval) {
            ci.cancel();
            return;
        }

        this.antikytheramechanism$wheelImpulseScale = (double) totalSubsteps / requestedUpdates;
    }

    /**
     * Offroad normally computes effective mass at each wheel point. uniform_spring_mass deliberately
     * removes the rotational/lever-arm contribution and uses total inverse mass only, making the
     * spring-mass scaling identical for all wheel positions on the same rigid body.
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/mass/MassData;getInverseNormalMass(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)D"),
            require = 1)
    private double antikytheramechanism$toggleEffectiveMassAxis(
            MassData massData,
            Vector3dc position,
            Vector3dc direction) {
        if (OffroadWheelDiagnostics.uniformSpringMass(this.antikytheramechanism$currentSubLevel)) {
            return massData.getInverseMass();
        }
        if (OffroadWheelDiagnostics.massAxisWorldUp(this.antikytheramechanism$currentSubLevel)) {
            Vector3d worldUpInPlot = this.antikytheramechanism$currentSubLevel.logicalPose()
                    .transformNormalInverse(new Vector3d(0.0, 1.0, 0.0));
            return massData.getInverseNormalMass(position, worldUpInPlot);
        }
        return massData.getInverseNormalMass(position, direction);
    }

    /**
     * The clamped spring length controls only the elastic spring term. Besides fully disabling it,
     * diagnostics can scale compression continuously or force maximum compression. The saturated mode
     * deliberately keeps a large elastic force while removing position-dependent spring feedback.
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"),
            require = 1)
    private double antikytheramechanism$diagnoseElasticSpring(double value, double min, double max) {
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.SPRING)) {
            return max;
        }
        if (OffroadWheelDiagnostics.springSaturated(this.antikytheramechanism$currentSubLevel)) {
            return min;
        }

        double clamped = Mth.clamp(value, min, max);
        double scale = OffroadWheelDiagnostics.springScale(this.antikytheramechanism$currentSubLevel);
        return max - (max - clamped) * scale;
    }

    /**
     * Offroad reads localVelocity.y exactly once to calculate vertical damping. Zero only that read;
     * X/Z remain untouched for the horizontal wheel terms.
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "FIELD",
                    target = "Lorg/joml/Vector3d;y:D",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0),
            require = 1)
    private double antikytheramechanism$toggleVerticalDamping(Vector3d localVelocity) {
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.DAMPING)) {
            return 0.0;
        }
        return localVelocity.y;
    }

    /**
     * The second Vector3d#set(DDD) in sable$physicsTick seeds queuedForce with the suspension
     * (elastic spring + vertical damping) impulse. Capture that exact vector before tire forces are
     * added so its torque can be isolated independently at the final ForceTotal application.
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;set(DDD)Lorg/joml/Vector3d;",
                    ordinal = 1),
            require = 1)
    private Vector3d antikytheramechanism$captureSuspensionImpulse(
            Vector3d queuedForce,
            double x,
            double y,
            double z) {
        this.antikytheramechanism$suspensionImpulse.set(x, y, z);
        return queuedForce.set(x, y, z);
    }

    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;",
                    ordinal = 0),
            require = 1)
    private Vector3d antikytheramechanism$toggleLongitudinalForce(
            Vector3d queuedForce,
            double scalar,
            Vector3dc direction) {
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.LONGITUDINAL)) {
            return queuedForce;
        }
        return queuedForce.fma(scalar, direction);
    }

    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;",
                    ordinal = 1),
            require = 1)
    private Vector3d antikytheramechanism$toggleLateralForce(
            Vector3d queuedForce,
            double scalar,
            Vector3dc direction) {
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.LATERAL)) {
            return queuedForce;
        }
        return queuedForce.fma(scalar, direction);
    }

    /**
     * ALL recreates the aggressive no-force diagnostic. TORQUE removes torque from every wheel term.
     * suspension_no_torque is narrower: spring+damping keep their complete linear impulse but are
     * applied without r x F, while the remaining longitudinal/lateral impulse is still applied at the
     * real wheel point and therefore retains its normal torque.
     *
     * <p>With all diagnostic overrides in their normal state, the continuous-suspension prototype gets
     * first ownership of the spring+damping contribution. Only that contribution is deferred; the
     * tire remainder is still accumulated and applied by Offroad normally.</p>
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"),
            require = 1)
    private void antikytheramechanism$toggleFinalWheelImpulse(
            ForceTotal forceTotal,
            ServerSubLevel subLevel,
            Vector3dc position,
            Vector3dc impulse) {
        if (OffroadWheelDiagnostics.isDisabled(subLevel, Term.ALL)) {
            return;
        }

        double cadenceScale = this.antikytheramechanism$wheelImpulseScale;
        Vector3dc effectiveImpulse = impulse;
        if (cadenceScale != 1.0) {
            effectiveImpulse = new Vector3d(impulse).mul(cadenceScale);
        }

        // Explicit diagnostic overrides take precedence over the prototype so old A/B commands remain
        // meaningful instead of silently mixing two different force paths.
        if (OffroadWheelDiagnostics.isDisabled(subLevel, Term.TORQUE)) {
            forceTotal.applyLinearImpulse(effectiveImpulse);
            return;
        }
        if (OffroadWheelDiagnostics.suspensionNoTorque(subLevel)) {
            Vector3d suspension = new Vector3d(this.antikytheramechanism$suspensionImpulse).mul(cadenceScale);
            Vector3d tireRemainder = new Vector3d(effectiveImpulse).sub(suspension);
            forceTotal.applyLinearImpulse(suspension);
            forceTotal.applyImpulseAtPoint(subLevel, position, tireRemainder);
            return;
        }

        Vector3d suspension = new Vector3d(this.antikytheramechanism$suspensionImpulse).mul(cadenceScale);
        if (OffroadContinuousSuspensionPrototype.captureSuspension(subLevel, position, suspension)) {
            Vector3d tireRemainder = new Vector3d(effectiveImpulse).sub(suspension);
            forceTotal.applyImpulseAtPoint(subLevel, position, tireRemainder);
            return;
        }

        forceTotal.applyImpulseAtPoint(subLevel, position, effectiveImpulse);
    }

    /**
     * ForceTotal normally wakes a sleeping body when its accumulated wheel force/torque changes enough.
     * wheel_no_wake keeps the exact same impulses but submits them with wakeUp=false, then resets the
     * current accumulator. This isolates repeated wheel-driven wakeups from the force calculation itself.
     */
    @Redirect(
            method = "applyBatchedForces",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;applyForcesAndReset(Ldev/ryanhcode/sable/api/physics/force/ForceTotal;)V"),
            require = 1)
    private void antikytheramechanism$toggleWheelWakeup(
            RigidBodyHandle handle,
            ForceTotal forceTotal) {
        if (OffroadWheelDiagnostics.wheelNoWake(this.antikytheramechanism$currentSubLevel)) {
            handle.applyLinearAndAngularImpulse(
                    forceTotal.getLocalForce(),
                    forceTotal.getLocalTorque(),
                    false
            );
            forceTotal.reset();
            return;
        }
        handle.applyForcesAndReset(forceTotal);
    }
}
