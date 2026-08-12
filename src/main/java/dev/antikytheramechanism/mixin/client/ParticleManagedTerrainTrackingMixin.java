package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Sable from re-attaching or re-processing TerrainParticles that Antikythera has already
 * projected into ordinary parent-world space.
 */
@Mixin(value = Particle.class, priority = 900)
abstract class ParticleManagedTerrainTrackingMixin {
    @Inject(
            method = "sable$setTrackingSubLevel",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void antikytheramechanism$detachManagedTerrainDebris(
            ClientSubLevel subLevel,
            Vec3 particlePosition,
            CallbackInfo callback) {
        if ((Object) this instanceof TerrainParticle && MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            callback.cancel();
        }
    }

    /**
     * Sable's ParticleEngine calls this once on add and again before every particle tick. Detached
     * mini debris has already been projected by the TerrainParticle constructor and must never run
     * Sable's plot lookup/projection path again.
     */
    @Inject(
            method = "sable$initialKickOut",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void antikytheramechanism$skipInitialKickForDetachedDebris(CallbackInfo callback) {
        if ((Object) this instanceof ManagedTerrainParticleState state
                && state.antikytheramechanism$isDetachedFromSubLevel()) {
            callback.cancel();
        }
    }

    /**
     * Sable wraps Particle#move and allocates/query-checks several sublevel collision structures even
     * when ParticleSubLevelKickable says there are no relevant SubLevels. Once our TerrainParticle is
     * detached, invoke the vanilla operation carried by Sable's wrapper directly and cancel the
     * wrapper body before any of those allocations occur.
     */
    @Inject(
            method = "sable$moveWithSubLevels",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void antikytheramechanism$useVanillaMoveForDetachedDebris(
            double motionX,
            double motionY,
            double motionZ,
            Operation<Void> original,
            CallbackInfo callback) {
        if (!((Object) this instanceof ManagedTerrainParticleState state)
                || !state.antikytheramechanism$isDetachedFromSubLevel()) {
            return;
        }

        original.call(motionX, motionY, motionZ);
        callback.cancel();
    }
}
