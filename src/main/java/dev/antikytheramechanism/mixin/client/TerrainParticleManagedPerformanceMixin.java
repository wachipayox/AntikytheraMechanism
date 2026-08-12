package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Block debris from a managed 0.5 SubLevel is already projected into world space by Sable's
 * ParticleEngine hook. It does not need to run Sable's full moving-sublevel collision search for
 * every fragment every tick. A single broken block can spawn enough TerrainParticles for that
 * broadphase/raycast/voxel loop to dominate a frame.
 *
 * <p>Do not identify the particle only through Sable's transient tracking field. TerrainParticle
 * permanently retains the BlockPos whose block state produced the debris, and that position stays
 * in the plot even after Sable projects the particle coordinates into the parent world. Using it as
 * the primary marker makes the fast path stable for crack and destroy particles alike.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin implements ParticleSubLevelKickable {
    @Shadow
    @Final
    private BlockPos pos;

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
        if (MiniWorldEnvironment.isManagedSubLevel(Sable.HELPER.getContainingClient(this.pos))) {
            return true;
        }
        return MiniWorldEnvironment.isManagedSubLevel(
                ((ParticleExtension) (Object) this).sable$getTrackingSubLevel());
    }
}
