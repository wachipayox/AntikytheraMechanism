package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents Create/Catnip placement helpers from consuming an item after selecting a target outside
 * an Antikythera FrameMask.
 *
 * <p>Catnip's NeoForge hook writes the block with setBlockAndUpdate but does not check that call's
 * boolean result before PlacementOffset later shrinks the survival stack. Preflight the helper's
 * chosen target before that write occurs so an invalid mini-world placement fails atomically.</p>
 */
@Mixin(PlacementOffset.class)
abstract class PlacementOffsetFrameMaskMixin {
    @Inject(method = "placeInWorld", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$rejectTargetOutsideFrameMask(
            Level world,
            BlockItem blockItem,
            Player player,
            InteractionHand hand,
            BlockHitResult ray,
            CallbackInfoReturnable<ItemInteractionResult> callback) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos source = ray.getBlockPos();
        if (!ManagedMiniPlacementTargets.isManagedSource(world, source)) {
            return;
        }

        BlockPos target = ((PlacementOffset) (Object) this).getBlockPos();
        if (!ManagedMiniPlacementTargets.isOwnedTarget(serverLevel, source, target)) {
            callback.setReturnValue(ItemInteractionResult.FAIL);
        }
    }
}
