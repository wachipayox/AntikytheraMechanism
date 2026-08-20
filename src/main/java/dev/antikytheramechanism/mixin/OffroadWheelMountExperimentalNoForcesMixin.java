package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics;
import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics.Term;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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

    /** Inputs needed to replace Offroad's explicit spring step with a stable implicit step. */
    @Unique
    private double antikytheramechanism$timeStep;
    @Unique
    private double antikytheramechanism$normalMass = Double.NaN;
    @Unique
    private double antikytheramechanism$springCompression;
    @Unique
    private double antikytheramechanism$verticalVelocity;

    @Inject(method = "sable$physicsTick", at = @At("HEAD"), require = 1)
    private void antikytheramechanism$captureDiagnosticSubLevel(
            ServerSubLevel subLevel,
            RigidBodyHandle handle,
            double timeStep,
            CallbackInfo ci) {
        this.antikytheramechanism$currentSubLevel = subLevel;
        this.antikytheramechanism$suspensionImpulse.zero();
        this.antikytheramechanism$timeStep = timeStep;
        this.antikytheramechanism$normalMass = Double.NaN;
        this.antikytheramechanism$springCompression = 0.0;
        this.antikytheramechanism$verticalVelocity = 0.0;
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
        final double inverseMass;
        if (OffroadWheelDiagnostics.uniformSpringMass(this.antikytheramechanism$currentSubLevel)) {
            inverseMass = massData.getInverseMass();
        } else if (OffroadWheelDiagnostics.massAxisWorldUp(this.antikytheramechanism$currentSubLevel)) {
            Vector3d worldUpInPlot = this.antikytheramechanism$currentSubLevel.logicalPose()
                    .transformNormalInverse(new Vector3d(0.0, 1.0, 0.0));
            inverseMass = massData.getInverseNormalMass(position, worldUpInPlot);
        } else {
            inverseMass = massData.getInverseNormalMass(position, direction);
        }

        if (inverseMass > 0.0 && Double.isFinite(inverseMass)) {
            this.antikytheramechanism$normalMass = 1.0 / inverseMass;
        }
        return inverseMass;
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
        final double springLength;
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.SPRING)) {
            springLength = max;
        } else if (OffroadWheelDiagnostics.springSaturated(this.antikytheramechanism$currentSubLevel)) {
            springLength = min;
        } else {
            double clamped = Mth.clamp(value, min, max);
            double scale = OffroadWheelDiagnostics.springScale(this.antikytheramechanism$currentSubLevel);
            springLength = max - (max - clamped) * scale;
        }

        this.antikytheramechanism$springCompression = Math.max(0.0, max - springLength);
        return springLength;
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
        final double velocity = OffroadWheelDiagnostics.isDisabled(
                this.antikytheramechanism$currentSubLevel, Term.DAMPING) ? 0.0 : localVelocity.y;
        this.antikytheramechanism$verticalVelocity = velocity;
        return velocity;
    }

    /**
     * The second Vector3d#set(DDD) in sable$physicsTick seeds queuedForce with the suspension
     * (elastic spring + vertical damping) impulse. Capture that exact vector before tire forces are
     * added so its torque can be isolated independently at the final ForceTotal application.
     *
     * <p>Offroad's original impulse is explicit Euler: J = (k*x - c*v)*dt, with k = 40*c by
     * construction. At the normal two Sable substeps that stiff explicit spring can feed contact jitter
     * back into the rigid body; the user's runtime reproduction converges away only at very high
     * substep counts. Reconstruct k/c from the already-computed impulse and use the implicit-Euler
     * closed form instead:</p>
     *
     * <pre>
     * J = dt * (k*x - (c + dt*k)*v) / (1 + dt*c/m + dt^2*k/m)
     * </pre>
     *
     * <p>This tends to Offroad's original expression as dt -> 0, while remaining stable for a stiff
     * spring at the stock dt. Spring-disabled diagnostics deliberately bypass this replacement so that
     * command keeps its original meaning.</p>
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
        Vector3d suspension = new Vector3d(x, y, z);

        if (!OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.SPRING)) {
            this.antikytheramechanism$stabilizeSuspensionImpulse(suspension);
        }

        this.antikytheramechanism$suspensionImpulse.set(suspension);
        return queuedForce.set(suspension.x, suspension.y, suspension.z);
    }

    @Unique
    private void antikytheramechanism$stabilizeSuspensionImpulse(Vector3d impulse) {
        final double dt = this.antikytheramechanism$timeStep;
        final double mass = this.antikytheramechanism$normalMass;
        final double compression = this.antikytheramechanism$springCompression;
        final double velocity = this.antikytheramechanism$verticalVelocity;
        final double magnitude = impulse.length();

        if (!(dt > 0.0) || !(mass > 0.0)
                || !Double.isFinite(dt) || !Double.isFinite(mass)
                || !Double.isFinite(compression) || !Double.isFinite(velocity)
                || !(magnitude > 1.0e-12) || !Double.isFinite(magnitude)) {
            return;
        }

        // Offroad defines springStrength = 40 * dampingStrength, therefore
        // J_explicit / dt = c * (40*x - v). This lets us recover c without linking to Offroad fields.
        final double response = 40.0 * compression - velocity;
        if (Math.abs(response) <= 1.0e-12) {
            return;
        }

        final double damping = magnitude / (Math.abs(response) * dt);
        final double stiffness = damping * 40.0;
        if (!(damping >= 0.0) || !Double.isFinite(damping) || !Double.isFinite(stiffness)) {
            return;
        }

        final double denominator = 1.0
                + dt * damping / mass
                + dt * dt * stiffness / mass;
        if (!(denominator > 0.0) || !Double.isFinite(denominator)) {
            return;
        }

        final double implicitImpulse = dt
                * (stiffness * compression - (damping + dt * stiffness) * velocity)
                / denominator;
        final double explicitImpulse = Math.copySign(magnitude, response);
        final double ratio = implicitImpulse / explicitImpulse;

        if (Double.isFinite(ratio)) {
            impulse.mul(ratio);
        }
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
        if (OffroadWheelDiagnostics.isDisabled(subLevel, Term.TORQUE)) {
            forceTotal.applyLinearImpulse(impulse);
            return;
        }
        if (OffroadWheelDiagnostics.suspensionNoTorque(subLevel)) {
            Vector3d suspension = new Vector3d(this.antikytheramechanism$suspensionImpulse);
            Vector3d tireRemainder = new Vector3d(impulse).sub(suspension);
            forceTotal.applyLinearImpulse(suspension);
            forceTotal.applyImpulseAtPoint(subLevel, position, tireRemainder);
            return;
        }
        forceTotal.applyImpulseAtPoint(subLevel, position, impulse);
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
