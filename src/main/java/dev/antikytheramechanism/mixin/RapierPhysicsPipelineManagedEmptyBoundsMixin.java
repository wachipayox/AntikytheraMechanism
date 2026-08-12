package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antikytheramechanism.sublevel.ManagedRapierBounds;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents Sable's EMPTY integer sentinel from being forwarded to Rapier as real local bounds. */
@Mixin(value = RapierPhysicsPipeline.class, priority = 2000, remap = false)
abstract class RapierPhysicsPipelineManagedEmptyBoundsMixin {
    @WrapOperation(
            method = "onStatsChanged",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;setLocalBounds(JIIIIIII)V",
                    remap = false))
    private void antikytheramechanism$finiteManagedEmptyBounds(
            long sceneHandle,
            int id,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            Operation<Void> original,
            @Local(argsOnly = true) ServerSubLevel subLevel) {
        ManagedRapierBounds.NativeBounds safe = ManagedRapierBounds.finiteEmptyBounds(subLevel);
        if (safe == null) {
            original.call(sceneHandle, id, minX, minY, minZ, maxX, maxY, maxZ);
            return;
        }

        original.call(
                sceneHandle,
                id,
                safe.minX(),
                safe.minY(),
                safe.minZ(),
                safe.maxX(),
                safe.maxY(),
                safe.maxZ());
    }
}
