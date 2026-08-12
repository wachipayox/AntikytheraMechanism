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
        ClipContext managedOnly = new ClipContext(
                context.getFrom(),
                context.getTo(),
                accessor.antikytheramechanism$getBlockMode(),
                accessor.antikytheramechanism$getFluidMode(),
                accessor.antikytheramechanism$getCollisionContext());
        ClipContextExtension managedExtension = (ClipContextExtension) managedOnly;
        managedExtension.sable$setIgnoreMainLevel(true);
        managedExtension.sable$setSubLevelIgnoring(subLevel -> !MiniWorldEnvironment.isManagedSubLevel(subLevel));

        BlockHitResult managedHit;
        ManagedScaleRaycastSupport.beginReentry();
        try {
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

        BlockHitResult existing = callback.getReturnValue();
        if (existing == null || existing.getType() == HitResult.Type.MISS) {
            callback.setReturnValue(managedHit);
            return;
        }

        SubLevel existingSubLevel = Sable.HELPER.getContaining(level, existing.getBlockPos());
        Vec3 existingWorldLocation = existingSubLevel == null
                ? existing.getLocation()
                : ManagedScaleRaycastSupport.projectHitLocation(level, existingSubLevel, existing.getLocation());
        double existingDistance = existingWorldLocation.distanceToSqr(rayStart);

        if (managedDistance + 1.0E-8 < existingDistance) {
            callback.setReturnValue(managedHit);
        }
    }
}
