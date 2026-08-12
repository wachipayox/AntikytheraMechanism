package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.sublevel.ManagedClientSubLevelTracking;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client counterpart to the managed empty server-bounds guard.
 *
 * <p>Sable's EMPTY plot sentinel uses extreme integer coordinates. The tracking packet calls
 * forceUpdateBounds before assigning the sub-level name, so an empty Antikythera ClientSubLevel
 * would otherwise transform that sentinel into an extreme world-space AABB during bootstrap.</p>
 */
@Mixin(value = SubLevel.class, priority = 2100)
abstract class SubLevelManagedEmptyClientBoundsMixin {
    @Shadow @Final
    protected BoundingBox3d globalBounds;

    @Shadow @Final
    protected BoundingBox3d lastGlobalBounds;

    @Inject(method = "updateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$keepManagedEmptyClientBoundsFinite(CallbackInfo callback) {
        if (!((Object) this instanceof ClientSubLevel clientSubLevel)) {
            return;
        }
        if (!ManagedClientSubLevelTracking.isActive()
                && !MiniWorldEnvironment.isManagedSubLevel(clientSubLevel)) {
            return;
        }

        BoundingBox3ic plotBounds = clientSubLevel.getPlot().getBoundingBox();
        if (plotBounds != null
                && plotBounds.minX() <= plotBounds.maxX()
                && plotBounds.minY() <= plotBounds.maxY()
                && plotBounds.minZ() <= plotBounds.maxZ()
                && plotBounds.volume() > 0.0) {
            return;
        }

        this.lastGlobalBounds.set(this.globalBounds);
        Vector3dc position = clientSubLevel.logicalPose().position();
        this.globalBounds.set(
                position.x(), position.y(), position.z(),
                position.x(), position.y(), position.z());
        callback.cancel();
    }
}
