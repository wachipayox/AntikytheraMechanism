package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps rendered particles out of Sable's SubLevel light path when Antikythera is the only nearby
 * SubLevel owner.
 *
 * <p>Sable injects directly into Particle#getLightColor. Competing with that HEAD injector proved
 * ordering-sensitive, so this mixin wraps the vanilla call site one level above in
 * SingleQuadParticle#renderRotatedQuad. Detached Antikythera terrain debris is already known to live
 * permanently in parent-world space, so it takes the parent light path immediately without doing a
 * transformed-sublevel broadphase at all. Other particles retain the managed-only classification
 * fallback, and foreign SubLevels retain Sable's original behaviour.</p>
 */
@Mixin(SingleQuadParticle.class)
abstract class SingleQuadParticleManagedLightMixin {
    @Unique
    private static final double ANTIKYTHERA_LIGHT_QUERY_AREA = 8.0;

    @Unique
    private long antikytheramechanism$lightClassificationTick = Long.MIN_VALUE;

    @Unique
    private boolean antikytheramechanism$managedOnlyLight;

    @WrapOperation(
            method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/SingleQuadParticle;getLightColor(F)I"))
    private int antikytheramechanism$useParentLightNearManagedSubLevels(
            SingleQuadParticle particle,
            float partialTick,
            Operation<Integer> original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return original.call(particle, partialTick);
        }

        // Terrain debris that Antikythera already detached from Sable is permanently in parent-world
        // coordinates. Do not ask Sable which transformed SubLevels intersect it: that query was
        // still running once per debris particle per render tick and was the remaining FPS hotspot
        // when a normal block broke next to a Mechanism Frame.
        if ((Object) particle instanceof ManagedTerrainParticleState state
                && state.antikytheramechanism$isDetachedFromSubLevel()) {
            return antikytheramechanism$parentLight(level, particle.getBoundingBox());
        }

        long gameTime = level.getGameTime();
        if (antikytheramechanism$lightClassificationTick != gameTime) {
            antikytheramechanism$lightClassificationTick = gameTime;
            antikytheramechanism$managedOnlyLight = antikytheramechanism$intersectsOnlyManagedSubLevels(
                    level,
                    particle.getBoundingBox());
        }

        if (!antikytheramechanism$managedOnlyLight) {
            return original.call(particle, partialTick);
        }

        return antikytheramechanism$parentLight(level, particle.getBoundingBox());
    }

    @Unique
    private static int antikytheramechanism$parentLight(ClientLevel level, AABB particleBounds) {
        BlockPos pos = BlockPos.containing(particleBounds.getCenter());
        return level.hasChunkAt(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
    }

    @Unique
    private static boolean antikytheramechanism$intersectsOnlyManagedSubLevels(
            ClientLevel level,
            AABB particleBounds) {
        BoundingBox3d query = new BoundingBox3d(particleBounds).expand(ANTIKYTHERA_LIGHT_QUERY_AREA);
        boolean foundManaged = false;
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, query)) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                foundManaged = true;
            } else {
                return false;
            }
        }
        return foundManaged;
    }
}
