package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Makes Create/Catnip placement helpers safe inside an Antikythera mini world.
 *
 * <p>Catnip writes the transformed state directly rather than going through BlockItem, so the
 * ordinary mini placement wrappers never provide virtual boundary support. Run the complete helper
 * write inside MiniWorldEnvironment on the server, preflight its independently chosen target on
 * both sides, and verify that a SUCCESS actually left the requested block in the world before an
 * item loss can become permanent.</p>
 */
@Mixin(PlacementOffset.class)
abstract class PlacementOffsetFrameMaskMixin {
    @WrapMethod(method = "placeInWorld")
    private ItemInteractionResult antikytheramechanism$placeManagedOffsetAtomically(
            Level world,
            BlockItem blockItem,
            Player player,
            InteractionHand hand,
            BlockHitResult ray,
            Operation<ItemInteractionResult> original) {
        BlockPos source = ray.getBlockPos();
        if (!ManagedMiniPlacementTargets.isManagedSource(world, source)) {
            return original.call(world, blockItem, player, hand, ray);
        }

        BlockPos target = ((PlacementOffset) (Object) this).getBlockPos();
        if (!ManagedMiniPlacementTargets.isOwnedTarget(world, source, target)) {
            return ItemInteractionResult.FAIL;
        }

        if (!(world instanceof ServerLevel serverLevel)) {
            return original.call(world, blockItem, player, hand, ray);
        }

        ItemStack held = player.getItemInHand(hand);
        int countBefore = held.getCount();
        ItemInteractionResult result = MiniWorldEnvironment.withVirtualReads(
                () -> original.call(world, blockItem, player, hand, ray));

        // Catnip's NeoForge hook does not inspect setBlockAndUpdate's boolean return. A helper can
        // therefore report SUCCESS even if a lower-level guard rejected/restored the write. Treat
        // that as an atomic failure and undo any speculative survival consumption.
        if (result == ItemInteractionResult.SUCCESS
                && !serverLevel.getBlockState(target).is(blockItem.getBlock())) {
            if (held.getCount() < countBefore) {
                held.setCount(countBefore);
            }
            return ItemInteractionResult.FAIL;
        }

        return result;
    }
}
