package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps block debris out of Sable's expensive moving-sublevel collision and lighting paths when
 * the relevant nearby SubLevels belong only to Antikythera.
 *
 * <p>The light bypass intentionally injects into TerrainParticle#getLightColor rather than
 * Particle#getLightColor. Sable also injects at the head of Particle#getLightColor, and mixin
 * application order can place Sable's callback ahead of another HEAD injector. Cancelling the
 * TerrainParticle override prevents its call to super entirely, so Sable's Particle light probe
 * cannot run for managed debris.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin implements ParticleSubLevelKickable {
    @Unique
    private static final double ANTIKYTHERA_LIGHT_QUERY_AREA = 8.0;

    @Shadow
    @Final
    private BlockPos pos;

    /**
     * Positive-only cache. TerrainParticle#pos permanently keeps the source block's plot position,
     * so once a fragment is proven to come from an Antikythera SubLevel it remains managed debris
     * for its whole lifetime. Never cache false: tracking can be established a few instructions
     * later during particle bootstrap.
     */
    @Unique
    private boolean antikytheramechanism$confirmedManagedDebris;

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

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useParentWorldLight(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        if (!antikytheramechanism$shouldUseVanillaParentLight()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            callback.setReturnValue(0);
            return;
        }

        Particle particle = (Particle) (Object) this;
        Vec3 center = particle.getBoundingBox().getCenter();
        BlockPos currentPos = BlockPos.containing(center.x, center.y, center.z);

        int light = level.hasChunkAt(currentPos)
                ? LevelRenderer.getLightColor(level, currentPos)
                : 0;

        // TerrainParticle's vanilla override falls back to the source block's light if the
        // particle-position lookup is zero. Preserve that behavior without calling super.
        if (light == 0 && level.hasChunkAt(this.pos)) {
            light = LevelRenderer.getLightColor(level, this.pos);
        }

        callback.setReturnValue(light);
    }

    @Unique
    private boolean antikytheramechanism$shouldUseVanillaParentLight() {
        return antikytheramechanism$isManagedDebris()
                || antikytheramechanism$intersectsOnlyManagedSubLevels(ANTIKYTHERA_LIGHT_QUERY_AREA);
    }

    @Unique
    private boolean antikytheramechanism$isManagedDebris() {
        if (this.antikytheramechanism$confirmedManagedDebris) {
            return true;
        }

        boolean managed = MiniWorldEnvironment.isManagedSubLevel(Sable.HELPER.getContainingClient(this.pos))
                || MiniWorldEnvironment.isManagedSubLevel(
                        ((ParticleExtension) (Object) this).sable$getTrackingSubLevel());
        if (managed) {
            this.antikytheramechanism$confirmedManagedDebris = true;
        }
        return managed;
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
