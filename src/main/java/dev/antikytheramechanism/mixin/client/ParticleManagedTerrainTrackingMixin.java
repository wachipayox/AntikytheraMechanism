package dev.antikytheramechanism.mixin.client;

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
 * Sable projects a plot particle into parent-world coordinates in sable$initialKickOut and then
 * normally keeps the particle attached to the source SubLevel. Block debris does not need that
 * attachment: keeping dozens of TerrainParticles tracked makes Sable recalculate the SubLevel pose
 * for every fragment every tick even when collision searching has already been disabled.
 *
 * <p>This mixin runs after Sable's Particle mixin and cancels only its injected tracking setter for
 * TerrainParticles originating from an Antikythera SubLevel. Their position has already been
 * projected by Sable, so subsequent movement is ordinary world-space particle movement.</p>
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
}
