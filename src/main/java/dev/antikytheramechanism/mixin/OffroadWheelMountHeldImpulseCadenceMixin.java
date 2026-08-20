package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadWheelHeldImpulseDiagnostics;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Diagnostic that keeps Rapier's high-frequency impulse delivery while deliberately lowering how often
 * Create Offroad recomputes the Wheel Mount force vector.
 *
 * <p>At a retained recompute substep, Offroad runs normally and the resulting small substep impulse is
 * cached. During the intervening substeps, the complete Wheel Mount calculation is skipped and that
 * cached impulse is replayed at the same wheel point. Therefore, with Sable at 30 substeps and this
 * diagnostic at two recomputes per tick, Rapier still receives 30 small wheel impulses but only two
 * raycast/compression/velocity evaluations.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false, priority = 1100)
abstract class OffroadWheelMountHeldImpulseCadenceMixin {
    @Shadow
    @Final
    private static Collection<Object> queuedWheelMounts;

    @Shadow
    @Final
    private ForceTotal forceTotal;

    @Shadow
    @Final
    private Vector3d queuedForcePos;

    @Shadow
    @Final
    private Vector3d queuedForce;

    @Unique
    private final Vector3d antikytheramechanism$heldImpulse = new Vector3d();

    @Unique
    private final Vector3d antikytheramechanism$heldPosition = new Vector3d();

    @Unique
    private boolean antikytheramechanism$heldImpulseValid;

    @Unique
    private boolean antikytheramechanism$heldCadenceActive;

    @Inject(method = "sable$physicsTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void antikytheramechanism$holdBetweenRecomputes(
            ServerSubLevel subLevel,
            RigidBodyHandle handle,
            double timeStep,
            CallbackInfo ci) {
        int requestedRecomputes = OffroadWheelHeldImpulseDiagnostics.recomputesPerTick(subLevel);
        this.antikytheramechanism$heldCadenceActive = false;

        if (requestedRecomputes <= 0) {
            this.antikytheramechanism$heldImpulseValid = false;
            return;
        }

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        int totalSubsteps = physicsSystem.getConfig().substepsPerTick;
        if (totalSubsteps <= 0 || requestedRecomputes >= totalSubsteps) {
            this.antikytheramechanism$heldImpulseValid = false;
            return;
        }

        this.antikytheramechanism$heldCadenceActive = true;

        int currentSubstep = (int) Math.round(physicsSystem.getPartialPhysicsTick() * totalSubsteps) - 1;
        currentSubstep = Math.max(0, Math.min(totalSubsteps - 1, currentSubstep));

        int currentInterval = currentSubstep * requestedRecomputes / totalSubsteps;
        int previousInterval = currentSubstep == 0
                ? -1
                : (currentSubstep - 1) * requestedRecomputes / totalSubsteps;

        // Recompute at the beginning of each coarse interval. Invalidate first so an early return from
        // Offroad (no tire/no terrain) cannot accidentally keep replaying the previous interval's force.
        if (currentInterval != previousInterval) {
            this.antikytheramechanism$heldImpulseValid = false;
            return;
        }

        // Between recomputes, deliver the last *small substep* impulse unchanged. This preserves one
        // physical wheel impulse per Rapier step without re-running raycast/compression/velocity logic.
        if (this.antikytheramechanism$heldImpulseValid) {
            this.forceTotal.applyImpulseAtPoint(
                    subLevel,
                    this.antikytheramechanism$heldPosition,
                    this.antikytheramechanism$heldImpulse);
            queuedWheelMounts.add(this);
        }

        ci.cancel();
    }

    /**
     * Offroad queues a Wheel Mount only after it has successfully computed and accumulated its current
     * force. Capture the exact raw small-substep impulse immediately before that queue insertion.
     */
    @Inject(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z",
                    shift = At.Shift.BEFORE),
            require = 1)
    private void antikytheramechanism$captureRecomputedImpulse(
            ServerSubLevel subLevel,
            RigidBodyHandle handle,
            double timeStep,
            CallbackInfo ci) {
        if (!this.antikytheramechanism$heldCadenceActive) {
            return;
        }

        this.antikytheramechanism$heldPosition.set(this.queuedForcePos);
        this.antikytheramechanism$heldImpulse.set(this.queuedForce);
        this.antikytheramechanism$heldImpulseValid = true;
    }
}
