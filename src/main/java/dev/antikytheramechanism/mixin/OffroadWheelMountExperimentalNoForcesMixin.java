package dev.antikytheramechanism.mixin;

import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Aggressive diagnostic patch for Create Offroad wheel mounts.
 *
 * <p>The wheel tick is allowed to run normally: tire lookup, terrain raycasts, suspension extension,
 * spring/damping computation, motor/braking and lateral grip all execute. The single final call that
 * contributes the accumulated wheel impulse/torque to Sable's {@link ForceTotal} is suppressed.
 * This isolates "wheel-generated rigid-body impulses" from every other side effect of merely having
 * a tire installed in the Wheel Mount.</p>
 *
 * <p>There is exactly one matching force-application call in the Offroad version under test, so
 * {@code require = 1} deliberately fails loudly if the implementation changes instead of silently
 * producing a useless diagnostic build.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
abstract class OffroadWheelMountExperimentalNoForcesMixin {
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"),
            require = 1)
    private void antikytheramechanism$suppressAllWheelImpulses(
            ForceTotal forceTotal,
            ServerSubLevel subLevel,
            Vector3dc position,
            Vector3dc impulse) {
        // Diagnostic no-op: preserve all wheel calculations but do not feed their impulse into Sable.
    }
}
