package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.interaction.MicroMacroBoundaryPlacement;
import dev.antikytheramechanism.sublevel.MiniFluidPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
abstract class BucketItemMiniFluidPolicyMixin {
    @Shadow @Final private Fluid content;

    /*
     * BucketItem is not a BlockItem and therefore never reaches ItemStackMiniPlacementMixin's
     * MicroMacroBoundaryPlacement router. Rewrite only the filled-bucket POV raycast here: when the
     * player clicked the outward face of an edge mini block, vanilla receives the equivalent hit on
     * the physical Frame. From that point onward BucketItem performs its normal macro placement or
     * waterlogging logic, and MiniFluidPolicy correctly sees a non-mini destination.
     *
     * The inherited static helper is invoked from BucketItem bytecode with BucketItem as the call-site
     * owner (not Item), so the target descriptor must use BucketItem or MixinExtras scans zero calls.
     */
    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/BucketItem;getPlayerPOVHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult antikytheramechanism$routeOutwardBucketHit(
            Level level,
            Player player,
            ClipContext.Fluid fluidMode,
            Operation<BlockHitResult> original) {
        BlockHitResult originalHit = original.call(level, player, fluidMode);
        if (content == Fluids.EMPTY) {
            return originalHit;
        }
        BlockHitResult routed = MicroMacroBoundaryPlacement.routeBucketHit(level, originalHit);
        return routed != null ? routed : originalHit;
    }

    /*
     * NeoForge 1.21.1 routes both normal BucketItem#use and vanilla dispenser bucket behavior through
     * this ItemStack-sensitive overload. Cancel before LiquidBlockContainer#placeLiquid so denied
     * water cannot waterlog a block, and before any source-fluid block, sound, game event or item
     * consumption is produced. Outward player clicks have already been rewritten to a macro target by
     * the use() raycast hook above, so they intentionally bypass this mini-only whitelist check.
     */
    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void antikytheramechanism$rejectDeniedMiniFluid(
            @Nullable Player player,
            Level level,
            BlockPos position,
            @Nullable BlockHitResult hitResult,
            @Nullable ItemStack container,
            CallbackInfoReturnable<Boolean> callback) {
        if (content != Fluids.EMPTY && !MiniFluidPolicy.allowsBucketFluid(level, position, content)) {
            callback.setReturnValue(false);
        }
    }
}
