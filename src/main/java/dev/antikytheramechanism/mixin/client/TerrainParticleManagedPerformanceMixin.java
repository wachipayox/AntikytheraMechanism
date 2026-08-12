package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import net.minecraft.client.particle.TerrainParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Block debris from a managed 0.5 SubLevel is already projected into world space by Sable's
 * ParticleEngine hook. It does not need to run Sable's full moving-sublevel collision search for
 * every fragment every tick. A single broken block can spawn enough TerrainParticles for that
 * broadphase/raycast/voxel loop to dominate a frame.
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin implements ParticleSubLevelKickable {
    @Override
    public boolean sable$shouldCareAboutIntersectingSubLevels() {
        return !antikytheramechanism$isManagedDebris();
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !antikytheramechanism$isManagedDebris();
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !antikytheramechanism$isManagedDebris();
    }

    @Unique
    private boolean antikytheramechanism$isManagedDebris() {
        return MiniWorldEnvironment.isManagedSubLevel(
                ((ParticleExtension) (Object) this).sable$getTrackingSubLevel());
    }
}
