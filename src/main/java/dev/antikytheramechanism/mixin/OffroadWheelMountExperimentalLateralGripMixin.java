package dev.antikytheramechanism.mixin;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Experimental compatibility patch for Create Offroad wheel mounts.
 *
 * <p>Offroad applies two {@link Vector3d#fma(double, Vector3dc)} corrections in
 * {@code WheelMountBlockEntity.sable$physicsTick}: the first is longitudinal braking/drive damping
 * and the second is lateral tire grip. The latter can couple tiny lateral point velocities into a
 * persistent chassis creep on resting vehicles. Until that solver is replaced with a bounded
 * effective-mass impulse, suppress only the lateral correction and leave suspension, propulsion and
 * longitudinal braking untouched.</p>
 *
 * <p>The target is named as a string and the mixin config plugin also gates this class on Offroad's
 * presence, so Antikythera keeps no hard dependency on Offroad.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
abstract class OffroadWheelMountExperimentalLateralGripMixin {
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;",
                    ordinal = 1),
            require = 0)
    private Vector3d antikytheramechanism$disableExperimentalLateralGrip(
            Vector3d queuedForce,
            double scalar,
            Vector3dc lateralDirection) {
        return queuedForce;
    }
}
