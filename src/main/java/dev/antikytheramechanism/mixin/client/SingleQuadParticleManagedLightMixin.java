package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
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
 * Keeps Antikythera terrain debris out of Sable's SubLevel light path.
 *
 * <p>The render decision is based primarily on the immutable origin classification written when the
 * TerrainParticle is constructed, not on Sable's current tracking pointer. This is intentionally one
 * call site above Particle#getLightColor, so Sable's HEAD injector is never entered for classified
 * Antikythera debris even if plot removal changed or cleared its tracking state.</p>
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

        // This is the normal path. It deliberately runs before any query of Sable state.
        if (state.antikytheramechanism$usesParentWorldPath()) {
            return antikytheramechanism$parentLight(level, particle.getBoundingBox());
        }

        // Defensive compatibility fallback for a TerrainParticle created through an unexpected path.
        // If Sable still tracks it in one of our SubLevels, promote it to the explicit parent path and
        // never call Particle#getLightColor for it again.
        SubLevel tracking = ((ParticleExtension) (Object) particle).sable$getTrackingSubLevel();
        if (MiniWorldEnvironment.isManagedSubLevel(tracking)) {
            state.antikytheramechanism$markParentWorldPath();
            state.antikytheramechanism$markDetachedFromSubLevel();
            return antikytheramechanism$parentLight(level, particle.getBoundingBox());
        }

        return original.call(particle, partialTick);
    }

    @Unique
    private static int antikytheramechanism$parentLight(ClientLevel level, AABB particleBounds) {
        BlockPos pos = BlockPos.containing(particleBounds.getCenter());
        return level.hasChunkAt(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
    }
}
