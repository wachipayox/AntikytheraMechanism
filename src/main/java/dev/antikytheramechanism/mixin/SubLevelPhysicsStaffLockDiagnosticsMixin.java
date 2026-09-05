package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.simulated.PhysicsStaffLockDiagnostics;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the first authoritative Sable pose writeback after a traced Physics Staff lock. */
@Mixin(SubLevelPhysicsSystem.class)
abstract class SubLevelPhysicsStaffLockDiagnosticsMixin {
    @Inject(method = "updateAllPoses", at = @At("TAIL"))
    private void antikytheramechanism$tracePhysicsStaffLockAfterSolver(
            ServerSubLevelContainer container,
            CallbackInfo ci) {
        PhysicsStaffLockDiagnostics.afterSolver(container);
    }
}
