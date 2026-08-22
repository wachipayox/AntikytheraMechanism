package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.sublevel.HostedMiniAerodynamicBridge;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Sable's native provider algorithm while replacing the physical recipient for managed minis.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
abstract class ServerSubLevelHostedMiniAerodynamicsMixin {
    @WrapOperation(
            method = "prePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider;sable$contributeLiftAndDrag(Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderContext;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3d;DLorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderGroup;)V",
                    ordinal = 0))
    private void antikytheramechanism$projectManagedMiniAerodynamics(
            BlockSubLevelLiftProvider provider,
            BlockSubLevelLiftProvider.LiftProviderContext context,
            ServerSubLevel logicalBody,
            Pose3d localPose,
            double timeStep,
            Vector3dc linearVelocity,
            Vector3dc angularVelocity,
            Vector3d linearImpulse,
            Vector3d angularImpulse,
            BlockSubLevelLiftProvider.LiftProviderGroup group,
            Operation<Void> original) {
        if (HostedMiniAerodynamicBridge.project(provider, context, logicalBody, timeStep)) {
            return;
        }
        original.call(
                provider,
                context,
                logicalBody,
                localPose,
                timeStep,
                linearVelocity,
                angularVelocity,
                linearImpulse,
                angularImpulse,
                group);
    }
}
