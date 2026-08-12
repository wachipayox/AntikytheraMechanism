package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps block debris out of Sable's expensive moving-sublevel collision path when the only
 * intersecting SubLevels belong to Antikythera.
 *
 * <p>Managed mini-world debris is already projected into parent-world space by Sable and does not
 * need to keep interacting physically with its source SubLevel. Parent-world destruction debris
 * next to a Mechanism Frame also does not need to raycast and resolve voxel collisions against the
 * stationary miniature world. Sable exposes {@link ParticleSubLevelKickable} specifically so
 * particle classes can opt out of that intersecting-SubLevel work; use that public hook instead of
 * injecting into a method added by Sable's own Particle mixin.</p>
 *
 * <p>If a TerrainParticle intersects any non-Antikythera SubLevel as well, stock Sable behavior is
 * preserved so this optimization cannot suppress interaction with unrelated Sable vessels.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin implements ParticleSubLevelKickable {
    @Shadow
    @Final
    private BlockPos pos;

    @Override
    public boolean sable$shouldCareAboutIntersectingSubLevels() {
        if (antikytheramechanism$isManagedDebris()) {
            return false;
        }
        return !antikytheramechanism$intersectsOnlyManagedSubLevels();
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

    @Unique
    private boolean antikytheramechanism$intersectsOnlyManagedSubLevels() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        Particle particle = (Particle) (Object) this;
        BoundingBox3d queryBounds = new BoundingBox3d(particle.getBoundingBox()).expand(0.5);
        boolean foundManaged = false;

        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(minecraft.level, queryBounds)) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                foundManaged = true;
            } else {
                // Keep normal Sable collision if any unrelated SubLevel is also involved.
                return false;
            }
        }

        return foundManaged;
    }
}
