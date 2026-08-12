package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Corrects Sable hit priority for Antikythera's uniformly scaled 0.5 SubLevels. */
@Mixin(value = BlockGetter.class, priority = 2000)
public interface BlockGetterManagedScaleRaycastMixin {
    @Unique
    ThreadLocal<Boolean> ANTIKYTHERA_RAYCAST_REENTRY = ThreadLocal.withInitial(() -> false);

    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$preferNearestManagedScaledHit(
            ClipContext context,
            CallbackInfoReturnable<BlockHitResult> callback) {
        if (ANTIKYTHERA_RAYCAST_REENTRY.get()) {
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
        ANTIKYTHERA_RAYCAST_REENTRY.set(true);
        try {
            managedHit = level.clip(managedOnly);
        } finally {
            ANTIKYTHERA_RAYCAST_REENTRY.remove();
        }

        if (managedHit.getType() == HitResult.Type.MISS) {
            return;
        }
        SubLevel managedSubLevel = Sable.HELPER.getContaining(level, managedHit.getBlockPos());
        if (!MiniWorldEnvironment.isManagedSubLevel(managedSubLevel)) {
            return;
        }

        Vec3 rayStart = context.getFrom();
        Vec3 managedWorldLocation = projectHitLocation(level, managedSubLevel, managedHit.getLocation());
        double managedDistance = managedWorldLocation.distanceToSqr(rayStart);

        BlockHitResult existing = callback.getReturnValue();
        if (existing == null || existing.getType() == HitResult.Type.MISS) {
            callback.setReturnValue(managedHit);
            return;
        }

        SubLevel existingSubLevel = Sable.HELPER.getContaining(level, existing.getBlockPos());
        Vec3 existingWorldLocation = existingSubLevel == null
                ? existing.getLocation()
                : projectHitLocation(level, existingSubLevel, existing.getLocation());
        double existingDistance = existingWorldLocation.distanceToSqr(rayStart);

        if (managedDistance + 1.0E-8 < existingDistance) {
            callback.setReturnValue(managedHit);
        }
    }

    @Unique
    private static Vec3 projectHitLocation(Level level, SubLevel subLevel, Vec3 localLocation) {
        Pose3dc pose = subLevel.logicalPose();
        if (level instanceof LevelPoseProviderExtension extension) {
            pose = extension.sable$getPose(subLevel);
        }
        Vector3dc projected = pose.transformPosition(JOMLConversion.toJOML(localLocation));
        return JOMLConversion.toMojang(projected);
    }
}
