package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps particles around Antikythera SubLevels out of Sable's unbounded vertical light scans.
 *
 * <p>Sable has two particle-light paths: one for a particle actively tracking a SubLevel, and one
 * for an untracked particle whose cached eight-block neighbourhood contains SubLevels. Both paths
 * walk down to {@code plot.getBoundingBox().minY()} to decide whether local skylight applies. Managed
 * Mechanism plots deliberately support EMPTY/special bounds, so either path can turn that walk into
 * an enormous render-thread loop.</p>
 *
 * <p>Tracked managed particles use direct parent/local samples in O(1). For the nearby path we cache
 * the same eight-block SubLevel set once per particle, like Sable does. We only take over if that set
 * contains an Antikythera SubLevel, preserve foreign SubLevels in the calculation, and make the
 * vertical test finite for managed plots: empty plots have no local ground to test, while non-empty
 * plots are clamped to the real client-level build minimum.</p>
 */
@Mixin(value = Particle.class, priority = 2000)
abstract class ParticleManagedSubLevelLightMixin {
    @Unique
    private static final double ANTIKYTHERA_LIGHT_QUERY_AREA = 8.0;

    @Shadow
    protected ClientLevel level;

    @Shadow
    public double x;

    @Shadow
    public double y;

    @Shadow
    public double z;

    @Unique
    private boolean antikytheramechanism$checkedNearbySubLevels;

    @Unique
    private boolean antikytheramechanism$hasManagedNearbySubLevel;

    @Unique
    private List<ClientSubLevel> antikytheramechanism$nearbySubLevels = List.of();

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useBoundedManagedSubLevelLight(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        SubLevel tracking = ((ParticleExtension) (Object) this).sable$getTrackingSubLevel();
        if (tracking instanceof ClientSubLevel clientSubLevel) {
            if (ManagedClientSubLevelIdentity.isManaged(clientSubLevel)) {
                callback.setReturnValue(antikytheramechanism$trackedManagedLight(clientSubLevel));
            }
            // Sable deliberately ignores nearby SubLevels while tracking one. Preserve that behavior
            // for foreign tracked SubLevels by leaving its injector untouched.
            return;
        }

        antikytheramechanism$cacheNearbySubLevels();
        if (!this.antikytheramechanism$hasManagedNearbySubLevel) {
            return;
        }

        callback.setReturnValue(antikytheramechanism$nearbyLight());
    }

    @Unique
    private int antikytheramechanism$trackedManagedLight(ClientSubLevel clientSubLevel) {
        BlockPos globalPos = BlockPos.containing(this.x, this.y, this.z);
        if (!this.level.hasChunkAt(globalPos)) {
            return 0;
        }

        int blockLight = this.level.getBrightness(LightLayer.BLOCK, globalPos);
        int skyLight = this.level.getBrightness(LightLayer.SKY, globalPos);
        if (clientSubLevel.isRemoved()) {
            return LightTexture.pack(blockLight, skyLight);
        }

        Vector3d localPosition = clientSubLevel.logicalPose()
                .transformPositionInverse(new Vector3d(this.x, this.y, this.z));
        BlockPos localPos = BlockPos.containing(localPosition.x, localPosition.y, localPosition.z);
        ClientLevel parentLevel = clientSubLevel.getLevel();
        if (parentLevel.hasChunkAt(localPos)) {
            blockLight = Math.max(
                    blockLight,
                    parentLevel.getBrightness(LightLayer.BLOCK, localPos));

            int localSky = clientSubLevel.scaleSkyLight(
                    parentLevel.getBrightness(LightLayer.SKY, localPos));
            skyLight = Math.min(skyLight, localSky);
        }

        return LightTexture.pack(blockLight, skyLight);
    }

    @Unique
    private void antikytheramechanism$cacheNearbySubLevels() {
        if (this.antikytheramechanism$checkedNearbySubLevels) {
            return;
        }
        this.antikytheramechanism$checkedNearbySubLevels = true;

        BlockPos globalPos = BlockPos.containing(this.x, this.y, this.z);
        List<ClientSubLevel> nearby = new ArrayList<>(4);
        Iterable<SubLevel> intersecting = Sable.HELPER.getAllIntersecting(
                this.level,
                new BoundingBox3d(globalPos).expand(ANTIKYTHERA_LIGHT_QUERY_AREA));
        for (SubLevel subLevel : intersecting) {
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
                continue;
            }
            nearby.add(clientSubLevel);
            if (ManagedClientSubLevelIdentity.isManaged(clientSubLevel)) {
                this.antikytheramechanism$hasManagedNearbySubLevel = true;
            }
        }
        this.antikytheramechanism$nearbySubLevels = List.copyOf(nearby);
    }

    @Unique
    private int antikytheramechanism$nearbyLight() {
        BlockPos globalPos = BlockPos.containing(this.x, this.y, this.z);
        if (!this.level.hasChunkAt(globalPos)) {
            return 0;
        }

        int blockLight = this.level.getBrightness(LightLayer.BLOCK, globalPos);
        int skyLight = this.level.getBrightness(LightLayer.SKY, globalPos);
        BoundingBox3d particleBounds = new BoundingBox3d(globalPos).expand(0.5);
        Vector3d localPosition = new Vector3d();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos heightmapPos = new BlockPos.MutableBlockPos();

        for (ClientSubLevel clientSubLevel : this.antikytheramechanism$nearbySubLevels) {
            if (clientSubLevel.isRemoved() || !clientSubLevel.boundingBox().intersects(particleBounds)) {
                continue;
            }

            clientSubLevel.logicalPose()
                    .transformPositionInverse(localPosition.set(this.x, this.y, this.z));
            localPos.set(localPosition.x, localPosition.y, localPosition.z);
            ClientLevel parentLevel = clientSubLevel.getLevel();
            if (!parentLevel.hasChunkAt(localPos)) {
                continue;
            }

            blockLight = Math.max(
                    blockLight,
                    parentLevel.getBrightness(LightLayer.BLOCK, localPos));

            if (!antikytheramechanism$isAboveLocalGround(
                    clientSubLevel,
                    parentLevel,
                    localPos,
                    heightmapPos)) {
                continue;
            }

            int localSky = clientSubLevel.scaleSkyLight(
                    parentLevel.getBrightness(LightLayer.SKY, localPos));
            skyLight = Math.min(skyLight, localSky);
        }

        return LightTexture.pack(blockLight, skyLight);
    }

    @Unique
    private static boolean antikytheramechanism$isAboveLocalGround(
            ClientSubLevel clientSubLevel,
            ClientLevel parentLevel,
            BlockPos localPos,
            BlockPos.MutableBlockPos heightmapPos) {
        LevelPlot plot = clientSubLevel.getPlot();
        BoundingBox3ic plotBounds = plot.getBoundingBox();
        boolean managed = ManagedClientSubLevelIdentity.isManaged(clientSubLevel);

        // EMPTY is a valid lifecycle state for a Mechanism plot. With no mini blocks there is no
        // local surface that should reduce this particle's skylight, and scanning the EMPTY sentinel
        // is precisely the render-thread freeze this guard exists to prevent.
        if (managed && antikytheramechanism$isEmpty(plotBounds)) {
            return false;
        }

        int lowerY = plotBounds.minY();
        if (managed) {
            lowerY = Math.max(lowerY, parentLevel.getMinBuildHeight());
        }

        heightmapPos.setWithOffset(localPos, Direction.UP);
        while (heightmapPos.getY() >= lowerY) {
            if (!parentLevel.getBlockState(heightmapPos).isAir()) {
                return true;
            }
            heightmapPos.move(Direction.DOWN);
        }
        return false;
    }

    @Unique
    private static boolean antikytheramechanism$isEmpty(BoundingBox3ic bounds) {
        return bounds == null
                || bounds.minX() > bounds.maxX()
                || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ()
                || bounds.volume() <= 0.0;
    }
}
