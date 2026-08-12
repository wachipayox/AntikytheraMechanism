package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedTerrainParticleLightAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable injects SubLevel-aware lighting at the head of Particle#getLightColor. Terrain debris near
 * an Antikythera mechanism does not need that path: it is rendered in parent-world space, and the
 * Sable light probe can retain/query managed SubLevels while their plot is empty or being removed.
 *
 * <p>Run before Sable's default-priority HEAD injector and return the exact vanilla Particle light
 * lookup. TerrainParticle's own override still receives this value from super and may apply its
 * normal block-debris adjustments.</p>
 */
@Mixin(value = Particle.class, priority = 2000)
abstract class ParticleManagedLightMixin {
    @Shadow
    public double x;
    @Shadow
    public double y;
    @Shadow
    public double z;
    @Shadow
    @Final
    protected ClientLevel level;

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useParentWorldLight(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        if (!((Object) this instanceof ManagedTerrainParticleLightAccess managed)
                || !managed.antikytheramechanism$shouldUseVanillaParentLight()) {
            return;
        }

        BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
        callback.setReturnValue(this.level.hasChunkAt(pos)
                ? LevelRenderer.getLightColor(this.level, pos)
                : 0);
    }
}
