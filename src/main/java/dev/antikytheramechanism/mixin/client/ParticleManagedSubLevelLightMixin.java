package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps particles tracking Antikythera SubLevels out of Sable's unbounded vertical light scan.
 *
 * <p>Sable's generic Particle#getLightColor hook walks downward from a tracked particle's local
 * position to the plot bounding-box minimum to decide whether local skylight should apply. Managed
 * Mechanism plots deliberately support EMPTY/special bounds, so that walk can become enormous and
 * freeze the render thread even for non-terrain particles such as smoke or dust. Our managed
 * SubLevels already maintain a safe ambient skylight scale; combine that with direct parent/local
 * light samples in O(1) instead of scanning the plot column.</p>
 *
 * <p>This only handles particles that Sable is actively tracking on an Antikythera-managed
 * ClientSubLevel. Foreign Sable SubLevels retain Sable's original lighting semantics.</p>
 */
@Mixin(value = Particle.class, priority = 2000)
abstract class ParticleManagedSubLevelLightMixin {
    @Shadow
    protected ClientLevel level;

    @Shadow
    public double x;

    @Shadow
    public double y;

    @Shadow
    public double z;

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useBoundedManagedSubLevelLight(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        SubLevel tracking = ((ParticleExtension) (Object) this).sable$getTrackingSubLevel();
        if (!(tracking instanceof ClientSubLevel clientSubLevel)
                || !ManagedClientSubLevelIdentity.isManaged(clientSubLevel)) {
            return;
        }

        BlockPos globalPos = BlockPos.containing(this.x, this.y, this.z);
        int blockLight = 0;
        int skyLight = 0;
        if (this.level.hasChunkAt(globalPos)) {
            blockLight = this.level.getBrightness(LightLayer.BLOCK, globalPos);
            skyLight = this.level.getBrightness(LightLayer.SKY, globalPos);
        }

        if (!clientSubLevel.isRemoved()) {
            Vector3d localPosition = clientSubLevel.logicalPose()
                    .transformPositionInverse(new Vector3d(this.x, this.y, this.z));
            BlockPos localPos = BlockPos.containing(localPosition.x, localPosition.y, localPosition.z);
            ClientLevel parentLevel = clientSubLevel.getLevel();

            if (parentLevel.hasChunkAt(localPos)) {
                blockLight = Math.max(
                        blockLight,
                        parentLevel.getBrightness(LightLayer.BLOCK, localPos));

                // The managed ClientSubLevel mixin computes this ambient scale from the exterior of
                // the miniature Frame volume without relying on empty plot bounds.
                int localSky = clientSubLevel.scaleSkyLight(
                        parentLevel.getBrightness(LightLayer.SKY, localPos));
                skyLight = Math.min(skyLight, localSky);
            }
        }

        callback.setReturnValue(LightTexture.pack(blockLight, skyLight));
    }
}
