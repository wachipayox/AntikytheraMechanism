package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.interaction.ManagedScaleRaycastSupport;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Corrects Sable hit priority for Antikythera's uniformly scaled 0.5 SubLevels.
 *
 * <p>Sable compares the main-world hit distance in world coordinates with a SubLevel hit distance
 * measured in the SubLevel's unscaled local coordinates. At scale 0.5 that can make a mini surface
 * win even though a parent-world surface is visibly closer to the camera.</p>
 *
 * <p>Do not infer whether Sable's returned hit belongs to a SubLevel from its BlockPos. A perfectly
 * ordinary parent-world block can physically overlap a SubLevel's world-space volume, so
 * {@code getContaining(level, hit.getBlockPos())} is ambiguous for a main-level hit. Instead run an
 * unambiguous parent-only raycast and an Antikythera-only raycast, project only the latter back to
 * world space, and compare like-for-like distances.</p>
 *
 * <p>Sable overwrites {@code BlockGetter#clip} at priority 1100. This mixin must run after that
 * overwrite has been merged; using a higher mixin priority lets Sable replace the already-injected
 * method and silently discards this correction.</p>
 */
@Mixin(value = BlockGetter.class, priority = 900)
public interface BlockGetterManagedScaleRaycastMixin {
    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$preferNearestManagedScaledHit(
            ClipContext context,
            CallbackInfoReturnable<BlockHitResult> callback) {
        if (ManagedScaleRaycastSupport.isReentrant()) {
            return;
        }
        if (!((Object) this instanceof Level level)) {
            return;
        }
        if (context instanceof ClipContextExtension extension && extension.sable$doNotProject()) {
            return;
        }

        ClipContextAccessor accessor = (ClipContextAccessor) context;

        // Pure vanilla/main-level ray. doNotProject makes Sable delegate directly to originalClip,
        // so the resulting BlockPos/location can never be mistaken for a SubLevel-local hit.
        ClipContext parentOnly = new ClipContext(
                context.getFrom(),
                context.getTo(),
                accessor.antikytheramechanism$getBlockMode(),
                accessor.antikytheramechanism$getFluidMode(),
                accessor.antikytheramechanism$getCollisionContext());
        ((ClipContextExtension) parentOnly).sable$setDoNotProject(true);

        // Antikythera-only ray. Keep Sable's normal inward projection here because the returned hit
        // must remain in plot coordinates for ordinary mini-block interaction/placement.
        ClipContext managedOnly = new ClipContext(
                context.getFrom(),
                context.getTo(),
                accessor.antikytheramechanism$getBlockMode(),
                accessor.antikytheramechanism$getFluidMode(),
                accessor.antikytheramechanism$getCollisionContext());
        ClipContextExtension managedExtension = (ClipContextExtension) managedOnly;
        managedExtension.sable$setIgnoreMainLevel(true);
        managedExtension.sable$setSubLevelIgnoring(subLevel -> !MiniWorldEnvironment.isManagedSubLevel(subLevel));

        BlockHitResult parentHit;
        BlockHitResult managedHit;
        ManagedScaleRaycastSupport.beginReentry();
        try {
            parentHit = level.clip(parentOnly);
            managedHit = level.clip(managedOnly);
        } finally {
            ManagedScaleRaycastSupport.endReentry();
        }

        if (managedHit.getType() == HitResult.Type.MISS) {
            return;
        }
        SubLevel managedSubLevel = Sable.HELPER.getContaining(level, managedHit.getBlockPos());
        if (!MiniWorldEnvironment.isManagedSubLevel(managedSubLevel)) {
            return;
        }

        Vec3 rayStart = context.getFrom();
        Vec3 managedWorldLocation = ManagedScaleRaycastSupport.projectHitLocation(
                level, managedSubLevel, managedHit.getLocation());
        double managedDistance = managedWorldLocation.distanceToSqr(rayStart);

        BlockHitResult best = managedHit;
        double bestDistance = managedDistance;
        if (parentHit.getType() != HitResult.Type.MISS) {
            double parentDistance = parentHit.getLocation().distanceToSqr(rayStart);
            if (parentDistance <= bestDistance + 1.0E-8) {
                best = parentHit;
                bestDistance = parentDistance;
            }
        }

        // Preserve a closer foreign Sable SubLevel if the original Sable result is clearly one.
        // Parent hits are deliberately ignored here because parentOnly above is authoritative and
        // cannot be confused by overlapping world-space SubLevel bounds.
        BlockHitResult existing = callback.getReturnValue();
        if (existing != null && existing.getType() != HitResult.Type.MISS) {
            SubLevel existingSubLevel = Sable.HELPER.getContaining(level, existing.getBlockPos());
            if (existingSubLevel != null && !MiniWorldEnvironment.isManagedSubLevel(existingSubLevel)) {
                Vec3 existingWorldLocation = ManagedScaleRaycastSupport.projectHitLocation(
                        level, existingSubLevel, existing.getLocation());
                double existingDistance = existingWorldLocation.distanceToSqr(rayStart);
                if (existingDistance + 1.0E-8 < bestDistance) {
                    best = existing;
                }
            }
        }

        callback.setReturnValue(best);
    }
}
