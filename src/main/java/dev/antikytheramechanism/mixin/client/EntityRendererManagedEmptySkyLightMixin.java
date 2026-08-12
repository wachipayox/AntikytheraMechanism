package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protects Sable's entity skylight scan from an empty managed plot.
 *
 * <p>Sable's merged helper walks downward one block at a time until
 * {@code plot.getBoundingBox().minY()}. An empty LevelPlot returns BoundingBox3i.EMPTY; during the
 * client-side window after the last mini block is removed, that sentinel can turn the scan into an
 * enormous loop on the render thread. The Antikythera freeze watchdog captured the render thread
 * inside exactly that helper. We only take over when an intersecting Antikythera plot is empty,
 * reproduce Sable's normal calculation for all non-empty SubLevels, and cap the downward scan at
 * the level's real build minimum.</p>
 */
@Mixin(value = EntityRenderer.class, priority = 900)
abstract class EntityRendererManagedEmptySkyLightMixin {
    @Inject(
            method = "sable$getSubLevelAccountedSkyLight",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private static void antikytheramechanism$skipEmptyManagedPlotInSkyScan(
            int original,
            Level instance,
            LightLayer lightLayer,
            BlockPos blockPos,
            Vector3dc probePosition,
            CallbackInfoReturnable<Integer> callback) {
        Iterable<SubLevel> intersecting = Sable.HELPER.getAllIntersecting(instance, new BoundingBox3d(blockPos));
        boolean hasManagedEmpty = false;
        for (SubLevel subLevel : intersecting) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)
                    && antikytheramechanism$isEmpty(subLevel.getPlot().getBoundingBox())) {
                hasManagedEmpty = true;
                break;
            }
        }
        if (!hasManagedEmpty) {
            return;
        }

        // Query again because the helper's Iterable is not contractually guaranteed to be reusable.
        intersecting = Sable.HELPER.getAllIntersecting(instance, new BoundingBox3d(blockPos));
        int baseBrightness = original == -1
                ? instance.getBrightness(lightLayer, blockPos)
                : LightTexture.sky(original);
        BlockPos.MutableBlockPos localPosition = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos heightmapPos = new BlockPos.MutableBlockPos();
        Vector3d tempProbePosition = new Vector3d();

        for (SubLevel subLevel : intersecting) {
            LevelPlot plot = subLevel.getPlot();
            BoundingBox3ic plotBounds = plot.getBoundingBox();
            if (antikytheramechanism$isEmpty(plotBounds)) {
                continue;
            }

            ClientSubLevel clientSubLevel = (ClientSubLevel) subLevel;
            clientSubLevel.renderPose().transformPositionInverse(probePosition, tempProbePosition);
            localPosition.set(tempProbePosition.x, tempProbePosition.y, tempProbePosition.z);

            Level subLevelLevel = subLevel.getLevel();
            heightmapPos.setWithOffset(localPosition, Direction.UP);
            int lowerY = Math.max(plotBounds.minY(), subLevelLevel.getMinBuildHeight());
            boolean isAboveGround = false;

            while (heightmapPos.getY() >= lowerY) {
                if (!subLevelLevel.getBlockState(heightmapPos).isAir()) {
                    isAboveGround = true;
                    break;
                }
                heightmapPos.move(Direction.DOWN);
            }

            if (!isAboveGround) {
                continue;
            }
            if (lightLayer == LightLayer.BLOCK) {
                baseBrightness = Math.max(
                        baseBrightness,
                        subLevelLevel.getBrightness(lightLayer, localPosition));
            } else if (lightLayer == LightLayer.SKY) {
                int brightness = clientSubLevel.scaleSkyLight(
                        subLevelLevel.getBrightness(lightLayer, localPosition));
                baseBrightness = Math.min(baseBrightness, brightness);
            }
        }

        callback.setReturnValue(baseBrightness);
    }

    private static boolean antikytheramechanism$isEmpty(BoundingBox3ic bounds) {
        return bounds == null
                || bounds.minX() > bounds.maxX()
                || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ()
                || bounds.volume() <= 0.0;
    }
}
