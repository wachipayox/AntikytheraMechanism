package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.antikytheramechanism.compat.sablescale.SableScalePivotCompensation;
import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fixes Sable's center-of-mass re-anchoring teleport for scaled SubLevels.
 *
 * <p>{@link MergedMassTracker} computes {@code deltaCoM = newCoM - oldCoM} and then moves the pose
 * position by {@code orientation * deltaCoM} before assigning the new rotation point. Because Sable
 * poses scale around that rotation point, the compensation must instead be
 * {@code orientation * scale * deltaCoM}. At scale 1 this is identical to stock behavior.</p>
 */
@Mixin(MergedMassTracker.class)
abstract class MergedMassTrackerScaledPivotMixin {
    @Shadow @Final private ServerSubLevel subLevel;

    @ModifyExpressionValue(
            method = "uploadData",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;sub(Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private Vector3d antikytheramechanism$scaleCenterOfMassPivotDelta(Vector3d movement) {
        return SableScalePivotCompensation.applyLocalScale(movement, subLevel.logicalPose().scale());
    }
}
