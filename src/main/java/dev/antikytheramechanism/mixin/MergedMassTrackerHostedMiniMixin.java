package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.HostedMiniMassBridge;
import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds managed child MassData to the physical foreign host before Sable uploads mass properties. */
@Mixin(MergedMassTracker.class)
abstract class MergedMassTrackerHostedMiniMixin {
    @Shadow @Final private ServerSubLevel subLevel;
    @Shadow private double mass;
    @Shadow private double inverseMass;
    @Shadow @Final private Matrix3d inertiaTensor;
    @Shadow private @Nullable Vector3d centerOfMass;

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Matrix3d;invert(Lorg/joml/Matrix3d;)Lorg/joml/Matrix3d;",
                    shift = At.Shift.BEFORE))
    private void antikytheramechanism$mergeHostedMiniMass(
            float partialPhysicsTick,
            CallbackInfo ci) {
        if (this.centerOfMass == null) {
            return;
        }

        HostedMiniMassBridge.MergedMass merged = HostedMiniMassBridge.mergeInto(
                this.subLevel,
                this.mass,
                this.centerOfMass,
                this.inertiaTensor);
        if (merged.mass() == this.mass) {
            return;
        }

        this.mass = merged.mass();
        this.inverseMass = 1.0 / this.mass;
        this.centerOfMass.set(merged.centerOfMass());
        this.inertiaTensor.set(merged.inertiaTensor());
    }
}
