package dev.antikytheramechanism.mixin;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Experimental compatibility patch used to isolate Create Offroad's wheel-force creep.
 *
 * <p>For this experiment both horizontal {@link Vector3d#fma(double, Vector3dc)} additions in
 * {@code WheelMountBlockEntity.sable$physicsTick} are suppressed. Offroad still performs its wheel
 * raycast and still computes/applies the suspension spring plus vertical damping, but contributes no
 * longitudinal braking/drive impulse and no lateral tire-grip impulse.</p>
 *
 * <p>There are exactly two matching calls in the Offroad version under test, so {@code require = 2}
 * intentionally makes a changed/incompatible Offroad implementation fail loudly rather than turning
 * this diagnostic into a silent no-op.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
abstract class OffroadWheelMountExperimentalSuspensionOnlyMixin {
    @Redirect(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;"),
            require = 2)
    private Vector3d antikytheramechanism$keepOnlySuspension(
            Vector3d queuedForce,
            double scalar,
            Vector3dc horizontalDirection) {
        return queuedForce;
    }
}
