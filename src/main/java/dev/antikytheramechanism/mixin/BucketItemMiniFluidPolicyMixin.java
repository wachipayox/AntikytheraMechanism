package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.interaction.AuthoritativePlacementSound;
import dev.antikytheramechanism.interaction.BucketRouteFeedbackContext;
import dev.antikytheramechanism.interaction.MicroMacroBoundaryPlacement;
import dev.antikytheramechanism.sublevel.MiniFluidPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Outward bucket placement is server-authoritative because the client does not own the FrameGraph
     * needed to map a mini face to its physical macro face. Keep that no-ghost rule, but remember that
     * the client did make a valid outward attempt so Minecraft still performs the normal hand swing.
     * The server route separately marks successful macro routing so its real placement sound can be
     * sent back to the placing player.
     */
    @WrapMethod(method = "use")
    private InteractionResultHolder<ItemStack> antikytheramechanism$restoreOutwardBucketFeedback(
            Level level,
            Player player,
            InteractionHand hand,
            Operation<InteractionResultHolder<ItemStack>> original) {
        BucketRouteFeedbackContext.enter();
        try {
            InteractionResultHolder<ItemStack> result = original.call(level, player, hand);
            if (level.isClientSide
                    && BucketRouteFeedbackContext.clientOutwardPrediction()
                    && !result.getResult().consumesAction()) {
                // Prediction only: do not place fluid or mutate the bucket client-side. SUCCESS is
                // enough for Minecraft's ordinary use-item path to swing the hand while the server
                // decides whether the mapped macro placement actually succeeds.
                return InteractionResultHolder.success(result.getObject());
            }
            return result;
        } finally {
            BucketRouteFeedbackContext.exit();
        }
    }

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
        if (routed != null) {
            if (level.isClientSide) {
                // routeBucketHit intentionally returns a MISS client-side for outward attempts so
                // vanilla cannot create a macro-fluid ghost without the authoritative FrameGraph.
                BucketRouteFeedbackContext.markClientOutwardPrediction();
            } else if (routed.getType() != HitResult.Type.MISS) {
                BucketRouteFeedbackContext.markServerAuthoritativeMacroPlacement();
            }
        }
        return routed != null ? routed : originalHit;
    }

    /**
     * Vanilla's bucket sound excludes the acting player because a normal client has already played
     * it. Our outward client intentionally did not execute BucketItem placement, so include that
     * player only after the server actually succeeds. Broadcast from physical/world coordinates so a
     * Frame hosted inside another Sable SubLevel does not leave the sound attached to plot storage.
     */
    @WrapOperation(
            method = "playEmptySound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void antikytheramechanism$playAuthoritativeOutwardBucketSound(
            LevelAccessor levelAccessor,
            @Nullable Player excludedPlayer,
            BlockPos position,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch,
            Operation<Void> original) {
        if (!BucketRouteFeedbackContext.serverAuthoritativeMacroPlacement()
                || !(levelAccessor instanceof Level level)) {
            original.call(levelAccessor, excludedPlayer, position, sound, source, volume, pitch);
            return;
        }

        Vec3 physical = AuthoritativePlacementSound.physicalSoundPosition(level, position);
        level.playSound(null, physical.x, physical.y, physical.z, sound, source, volume, pitch);
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
