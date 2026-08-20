package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics;
import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics.Term;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
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

    @Inject(method = "sable$physicsTick", at = @At("HEAD"), require = 1)
    private void antikytheramechanism$captureDiagnosticSubLevel(
            ServerSubLevel subLevel,
            RigidBodyHandle handle,
            double timeStep,
            CallbackInfo ci) {
        this.antikytheramechanism$currentSubLevel = subLevel;
    }

    /**
     * Setting springLength to its upper clamp bound makes the elastic part
     * (suspensionRestDistance - springLength) * springStrength exactly zero while preserving damping.
     */
    @Redirect(
            method = "sable$physicsTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"),
            require = 1)
    private double antikytheramechanism$toggleElasticSpring(double value, double min, double max) {
        if (OffroadWheelDiagnostics.isDisabled(this.antikytheramechanism$currentSubLevel, Term.SPRING)) {
            return max;
        }
        return Mth.clamp(value, min, max);
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
     * ALL recreates the previous aggressive no-force diagnostic. TORQUE keeps the complete calculated
     * wheel impulse but applies it as a pure linear impulse, removing only r x F from the wheel point.
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
        forceTotal.applyImpulseAtPoint(subLevel, position, impulse);
    }
}
