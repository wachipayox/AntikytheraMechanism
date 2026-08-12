package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedTerrainParticleLightAccess;
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
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps block debris out of Sable's expensive moving-sublevel collision and lighting paths when
 * the relevant nearby SubLevels belong only to Antikythera.
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin
        implements ParticleSubLevelKickable, ManagedTerrainParticleLightAccess {
    @Unique
    private static final double ANTIKYTHERA_LIGHT_QUERY_AREA = 8.0;

    @Shadow
    @Final
    private BlockPos pos;

    @Unique
    private byte antikytheramechanism$cachedLightMode;

    @Override
    public boolean sable$shouldCareAboutIntersectingSubLevels() {
        if (antikytheramechanism$isManagedDebris()) {
            return false;
        }
        return !antikytheramechanism$intersectsOnlyManagedSubLevels(0.5);
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !antikytheramechanism$isManagedDebris();
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !antikytheramechanism$isManagedDebris();
    }

    @Override
    public boolean antikytheramechanism$shouldUseVanillaParentLight() {
        if (antikytheramechanism$cachedLightMode != 0) {
            return antikytheramechanism$cachedLightMode == 1;
        }

        boolean shouldUseVanilla = antikytheramechanism$isManagedDebris()
                || antikytheramechanism$intersectsOnlyManagedSubLevels(ANTIKYTHERA_LIGHT_QUERY_AREA);
        antikytheramechanism$cachedLightMode = (byte) (shouldUseVanilla ? 1 : 2);
        return shouldUseVanilla;
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
    private boolean antikytheramechanism$intersectsOnlyManagedSubLevels(double expansion) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        Particle particle = (Particle) (Object) this;
        BoundingBox3d queryBounds;
        if (expansion >= ANTIKYTHERA_LIGHT_QUERY_AREA) {
            Vec3 center = particle.getBoundingBox().getCenter();
            BlockPos particlePos = BlockPos.containing(center.x, center.y, center.z);
            queryBounds = new BoundingBox3d(particlePos).expand(expansion);
        } else {
            queryBounds = new BoundingBox3d(particle.getBoundingBox()).expand(expansion);
        }

        boolean foundManaged = false;
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(minecraft.level, queryBounds)) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                foundManaged = true;
            } else {
                // Preserve stock Sable behavior if an unrelated SubLevel is also relevant.
                return false;
            }
        }
        return foundManaged;
    }
}
