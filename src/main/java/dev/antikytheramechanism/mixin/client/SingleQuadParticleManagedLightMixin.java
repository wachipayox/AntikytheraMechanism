package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
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
 * Keeps Antikythera terrain debris out of Sable's SubLevel light path.
 *
 * <p>Sable injects directly into Particle#getLightColor. Competing with that HEAD injector proved
 * ordering-sensitive, so this mixin wraps the vanilla call site one level above in
 * SingleQuadParticle#renderRotatedQuad. Terrain debris that Antikythera already detached uses parent
 * light directly. As a defensive fallback, debris that somehow reaches render while still tracking
 * one of our managed SubLevels is detached here before Sable can enter its expensive tracking light
 * path. Other particles and foreign Sable SubLevels keep Sable's original behaviour.</p>
 */
@Mixin(SingleQuadParticle.class)
abstract class SingleQuadParticleManagedLightMixin {
    @WrapOperation(
            method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/SingleQuadParticle;getLightColor(F)I"))
    private int antikytheramechanism$useParentLightForManagedTerrainDebris(
            SingleQuadParticle particle,
            float partialTick,
            Operation<Integer> original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !((Object) particle instanceof ManagedTerrainParticleState state)) {
            return original.call(particle, partialTick);
        }

        if (!state.antikytheramechanism$isDetachedFromSubLevel()) {
            ClientSubLevel tracking = ((ParticleExtension) (Object) particle).sable$getTrackingSubLevel();
            if (MiniWorldEnvironment.isManagedSubLevel(tracking)) {
                state.antikytheramechanism$markDetachedFromSubLevel();
            }
        }

        if (!state.antikytheramechanism$isDetachedFromSubLevel()) {
            return original.call(particle, partialTick);
        }

        return antikytheramechanism$parentLight(level, particle.getBoundingBox());
    }

    @Unique
    private static int antikytheramechanism$parentLight(ClientLevel level, AABB particleBounds) {
        BlockPos pos = BlockPos.containing(particleBounds.getCenter());
        return level.hasChunkAt(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
    }
}
