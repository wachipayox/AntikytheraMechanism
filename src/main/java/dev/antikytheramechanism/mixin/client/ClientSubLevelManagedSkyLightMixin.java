package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable scales the skylight of an entire ClientSubLevel by a single ambient sample. For very small
 * SubLevels it samples pose.position and Y +/- 1. A Mechanism SubLevel lives inside a normal block,
 * so the Y - 1 sample is commonly the real floor below the Frame. If that sample set resolves to
 * zero, Sable multiplies the whole miniature render by zero skylight and every block becomes black.
 *
 * <p>For our hollow miniature frames, ambient light must be sampled from the space immediately
 * outside the miniature volume, never from the solid floor underneath it. We therefore sample the
 * block above the occupied frame volume and its four horizontal sides and use the brightest value.
 * This preserves caves/enclosures while preventing a normal supporting floor from blacking out the
 * complete mini world.</p>
 */
@Mixin(value = ClientSubLevel.class, priority = 2000)
abstract class ClientSubLevelManagedSkyLightMixin {
    @Inject(method = "computeSubLevelSkyLight", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$sampleOutsideFrame(
            Pose3dc pose,
            CallbackInfoReturnable<Integer> callback) {
        ClientSubLevel self = (ClientSubLevel) (Object) this;
        if (!MiniWorldEnvironment.isManagedSubLevel(self)) {
            return;
        }

        ClientLevel level = self.getLevel();
        BoundingBox3dc bounds = self.boundingBox();

        if (bounds.volume() <= 1.0E-6) {
            int x = Mth.floor(pose.position().x());
            int y = Mth.floor(pose.position().y());
            int z = Mth.floor(pose.position().z());
            callback.setReturnValue(maxSky(
                    level,
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1)));
            return;
        }

        int minX = Mth.floor(bounds.minX());
        int minY = Mth.floor(bounds.minY());
        int minZ = Mth.floor(bounds.minZ());
        int maxX = Mth.floor(Math.nextDown(bounds.maxX()));
        int maxY = Mth.floor(Math.nextDown(bounds.maxY()));
        int maxZ = Mth.floor(Math.nextDown(bounds.maxZ()));
        int centerX = Mth.floor((bounds.minX() + bounds.maxX()) * 0.5);
        int centerY = Mth.floor((bounds.minY() + bounds.maxY()) * 0.5);
        int centerZ = Mth.floor((bounds.minZ() + bounds.maxZ()) * 0.5);

        callback.setReturnValue(maxSky(
                level,
                new BlockPos(centerX, maxY + 1, centerZ),
                new BlockPos(minX - 1, centerY, centerZ),
                new BlockPos(maxX + 1, centerY, centerZ),
                new BlockPos(centerX, centerY, minZ - 1),
                new BlockPos(centerX, centerY, maxZ + 1),
                new BlockPos(minX - 1, maxY + 1, minZ - 1),
                new BlockPos(maxX + 1, maxY + 1, maxZ + 1)));
    }

    private static int maxSky(ClientLevel level, BlockPos... positions) {
        int result = 0;
        for (BlockPos position : positions) {
            result = Math.max(result, level.getBrightness(LightLayer.SKY, position));
            if (result >= 15) {
                return 15;
            }
        }
        return result;
    }
}
