package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prevents Sable from reinterpreting already-global Antikythera parent debris as plot-local data.
 *
 * <p>Sable injects {@code ParticleExtension.sable$initialKickOut()} at the tail of vanilla
 * {@code ParticleEngine#add}. A parent-world TerrainParticle physically inside a Frame's world-space
 * SubLevel bounds can otherwise be mistaken for local plot debris and projected a second time.
 * This lower-priority mixin wraps exactly that injected call after Sable has merged it. Managed mini
 * debris is not detached yet at add-time, so it still receives its one intentional kick-out.</p>
 */
@Mixin(value = ParticleEngine.class, priority = 900)
abstract class ParticleEngineManagedAddMixin {
    @WrapOperation(
            method = "add",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/mixinterface/particle/ParticleExtension;sable$initialKickOut()V",
                    remap = false))
    private void antikytheramechanism$skipInitialKickForDetachedParentDebris(
            ParticleExtension extension,
            Operation<Void> original) {
        if ((Object) extension instanceof ManagedTerrainParticleState state
                && state.antikytheramechanism$isDetachedFromSubLevel()) {
            return;
        }
        original.call(extension);
    }
}
