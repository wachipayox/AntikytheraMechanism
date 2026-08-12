package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.ManagedSubLevelBounds;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable's empty plot sentinel contains extreme integer coordinates. Transforming that sentinel
 * as a real local AABB produces an extreme world-space Y range and makes ServerSubLevel remove
 * an otherwise valid, frame-owned empty mechanism on its next tick.
 *
 * <p>Managed empty mechanisms deliberately have no collision/raycast volume. Represent them in
 * Sable's world-space broadphase as a zero-volume point at their current pose until their first
 * real mini block gives the plot ordinary finite bounds.</p>
 */
@Mixin(value = SubLevel.class, priority = 2000)
abstract class SubLevelManagedEmptyGlobalBoundsMixin {
    @Shadow @Final
    protected BoundingBox3d globalBounds;

    @Shadow @Final
    protected BoundingBox3d lastGlobalBounds;

    @Inject(method = "updateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$keepManagedEmptyBoundsFinite(CallbackInfo callback) {
        if (!((Object) this instanceof ServerSubLevel serverSubLevel)
                || !ManagedSubLevelBounds.preserveIfEmpty(serverSubLevel)) {
            return;
        }

        this.lastGlobalBounds.set(this.globalBounds);
        Vector3dc position = serverSubLevel.logicalPose().position();
        this.globalBounds.set(
                position.x(), position.y(), position.z(),
                position.x(), position.y(), position.z());
        callback.cancel();
    }
}
