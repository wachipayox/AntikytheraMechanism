package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Antikythera mini worlds from going uniformly black when only a small part of their
 * world-space bounds intersects opaque parent terrain.
 *
 * <p>Sable intentionally compiles each mini block's own light into the sublevel mesh, then applies a
 * single parent-world skylight scale to the entire sublevel. Its small-sublevel fast path samples the
 * pose origin, so crossing a block boundary by a tiny amount can drop that global scale to zero even
 * while most of the Frame remains exposed. Managed mini worlds are tiny enough that sampling their
 * actual bounds is cheap and much more representative.</p>
 */
@Mixin(value = ClientSubLevel.class, remap = false)
abstract class ClientSubLevelManagedSkyLightMixin {
    private static final int FULL_SKY_LIGHT = 15;

    @Inject(method = "computeSubLevelSkyLight", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$sampleManagedMiniBounds(
            Pose3dc pose,
            CallbackInfoReturnable<Integer> callbackInfo) {
        ClientSubLevel child = (ClientSubLevel) (Object) this;
        int sableLight = callbackInfo.getReturnValue();
        if (sableLight >= FULL_SKY_LIGHT
                || !child.isFinalized()
                || !ManagedClientSubLevelIdentity.isManaged(child)) {
            return;
        }

        BoundingBox3dc bounds = child.boundingBox();
        if (!validBounds(bounds)) {
            return;
        }

        ClientLevel level = child.getLevel();
        double centerX = (bounds.minX() + bounds.maxX()) * 0.5;
        double centerY = (bounds.minY() + bounds.maxY()) * 0.5;
        double centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5;

        // First sample the same representative horizontal plane Sable uses for larger sublevels.
        int sampledLight = samplePlane(level, bounds, centerX, centerY, centerZ);

        // If the middle of a tiny Frame is inside terrain, its upper surface can still be exposed.
        // Sampling the top plane prevents one submerged corner/origin from blacking out the full mini
        // world, while a genuinely enclosed/cave-bound Frame still returns zero at every sample.
        if (sampledLight < FULL_SKY_LIGHT) {
            sampledLight = Math.max(
                    sampledLight,
                    samplePlane(level, bounds, centerX, bounds.maxY(), centerZ));
        }

        if (sampledLight > sableLight) {
            callbackInfo.setReturnValue(sampledLight);
        }
    }

    private static int samplePlane(
            ClientLevel level,
            BoundingBox3dc bounds,
            double centerX,
            double y,
            double centerZ) {
        int light = sample(level, centerX, y, centerZ);
        light = Math.max(light, sample(level, bounds.minX(), y, bounds.minZ()));
        light = Math.max(light, sample(level, bounds.maxX(), y, bounds.minZ()));
        light = Math.max(light, sample(level, bounds.minX(), y, bounds.maxZ()));
        light = Math.max(light, sample(level, bounds.maxX(), y, bounds.maxZ()));
        return light;
    }

    private static int sample(ClientLevel level, double x, double y, double z) {
        return level.getBrightness(LightLayer.SKY, BlockPos.containing(x, y, z));
    }

    private static boolean validBounds(BoundingBox3dc bounds) {
        return Double.isFinite(bounds.minX())
                && Double.isFinite(bounds.minY())
                && Double.isFinite(bounds.minZ())
                && Double.isFinite(bounds.maxX())
                && Double.isFinite(bounds.maxY())
                && Double.isFinite(bounds.maxZ())
                && bounds.maxX() > bounds.minX()
                && bounds.maxY() > bounds.minY()
                && bounds.maxZ() > bounds.minZ();
    }
}
