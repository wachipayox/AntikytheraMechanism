package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.interaction.ManagedScaleRaycastSupport;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * <p>Mechanism Frame bars and outer mini faces are intentionally coplanar. Frame priority is therefore
 * resolved from the exact 2/16 Frame shape the ray already hit, not by widening getShape() and not by
 * a fixed along-ray distance. The concrete bar AABB acts as the occluding volume, with a tiny 1/64
 * envelope only for render-pose interpolation. This remains stable at grazing angles while the
 * visible outline continues to match the model exactly.</p>
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

        // This correction exists for client interaction hit priority. Server-side clip() is also a
        // general-purpose visibility primitive used by explosions, AI and other simulation code.
        if (!level.isClientSide()) {
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
        Vec3 rayEnd = context.getTo();
        Vec3 managedWorldLocation = ManagedScaleRaycastSupport.projectHitLocation(
                level, managedSubLevel, managedHit.getLocation());
        double managedDistance = managedWorldLocation.distanceToSqr(rayStart);

        BlockHitResult best = managedHit;
        double bestPhysicalDistance = Double.POSITIVE_INFINITY;

        if (parentHit.getType() != HitResult.Type.MISS) {
            BlockState parentState = level.getBlockState(parentHit.getBlockPos());
            double parentDistance = parentHit.getLocation().distanceToSqr(rayStart);
            boolean frameHit = parentState.is(ModRegistries.MECHANISM_FRAME.get());
            boolean eligible;
            if (frameHit) {
                VoxelShape exactShape = context.getBlockShape(parentState, level, parentHit.getBlockPos());
                eligible = ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                        rayStart,
                        rayEnd,
                        parentHit.getBlockPos(),
                        exactShape,
                        parentHit.getLocation(),
                        managedWorldLocation);
            } else {
                eligible = ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                        parentDistance, managedDistance);
            }

            if (eligible) {
                best = parentHit;
                bestPhysicalDistance = parentDistance;
            }
        }

        // Preserve a closer foreign Sable SubLevel when appropriate. If that foreign hit is itself a
        // Mechanism Frame, run the same exact-bar arbitration in the candidate SubLevel's local space.
        BlockHitResult existing = callback.getReturnValue();
        if (existing != null && existing.getType() != HitResult.Type.MISS) {
            SubLevel existingSubLevel = Sable.HELPER.getContaining(level, existing.getBlockPos());
            if (existingSubLevel != null && !MiniWorldEnvironment.isManagedSubLevel(existingSubLevel)) {
                Vec3 existingWorldLocation = ManagedScaleRaycastSupport.projectHitLocation(
                        level, existingSubLevel, existing.getLocation());
                double existingDistance = existingWorldLocation.distanceToSqr(rayStart);
                BlockState existingState = existingSubLevel.getLevel().getBlockState(existing.getBlockPos());
                boolean frameHit = existingState.is(ModRegistries.MECHANISM_FRAME.get());
                boolean eligible;

                if (frameHit) {
                    Vec3 localRayStart = ManagedScaleRaycastSupport.unprojectWorldLocation(
                            level, existingSubLevel, rayStart);
                    Vec3 localRayEnd = ManagedScaleRaycastSupport.unprojectWorldLocation(
                            level, existingSubLevel, rayEnd);
                    Vec3 localManagedHit = ManagedScaleRaycastSupport.unprojectWorldLocation(
                            level, existingSubLevel, managedWorldLocation);
                    VoxelShape exactShape = context.getBlockShape(
                            existingState, existingSubLevel.getLevel(), existing.getBlockPos());
                    eligible = ManagedScaleRaycastSupport.shouldPreferFrameCandidate(
                            localRayStart,
                            localRayEnd,
                            existing.getBlockPos(),
                            exactShape,
                            existing.getLocation(),
                            localManagedHit);
                } else {
                    eligible = ManagedScaleRaycastSupport.shouldPreferPhysicalCandidate(
                            existingDistance, managedDistance);
                }

                if (eligible && existingDistance < bestPhysicalDistance) {
                    best = existing;
                    bestPhysicalDistance = existingDistance;
                }
            }
        }

        callback.setReturnValue(best);
    }
}
