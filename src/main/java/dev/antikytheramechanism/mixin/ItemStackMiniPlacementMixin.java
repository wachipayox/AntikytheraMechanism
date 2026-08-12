package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;

@Mixin(ItemStack.class)
abstract class ItemStackMiniPlacementMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult antikytheramechanism$routeAndTrackMiniPlacement(
            UseOnContext context,
            Operation<InteractionResult> original) {
        ItemStack self = (ItemStack) (Object) this;
        if (!(self.getItem() instanceof BlockItem blockItem)) {
            return original.call(context);
        }
        return runTrackedBlockUse(self, blockItem, context, true, () -> {
            InteractionResult routed = MiniPlacementRouter.route(blockItem, context);
            return routed != null ? routed : original.call(context);
        });
    }

    @WrapMethod(method = "onItemUseFirst")
    private InteractionResult antikytheramechanism$trackPlacementHelpers(
            UseOnContext context,
            Operation<InteractionResult> original) {
        ItemStack self = (ItemStack) (Object) this;
        if (!(self.getItem() instanceof BlockItem blockItem)) {
            return original.call(context);
        }
        /*
         * Vanilla calls ItemStack#onItemUseFirst before BlockState#useItemOn/useWithoutItem.
         * Never reject merely because the held BlockItem is not miniaturizable here: doing so
         * prevents an ordinary mini lever/button/etc. from receiving its own right-click. Actual
         * block writes are protected by FrameMaskWriteGuard while this tracked use is active.
         *
         * Create's cog/shaft placement guides also choose their own offset later in PlacementOffset,
         * so do not apply the ordinary BlockPlaceContext target preflight to this first-use hook.
         */
        return runTrackedBlockUse(self, blockItem, context, false, () -> original.call(context));
    }

    private static InteractionResult runTrackedBlockUse(
            ItemStack stack,
            BlockItem blockItem,
            UseOnContext context,
            boolean preflightVanillaTarget,
            Supplier<InteractionResult> action) {
        boolean managedSource = MiniWorldEnvironment.isManagedMiniPosition(
                context.getLevel(), context.getClickedPos());

        /*
         * A normal BlockItem used on a mini support can produce a relative BlockPlaceContext target
         * just outside the 2x2x2 FrameMask. Reject that target on both client and server before
         * BlockItem gets a chance to predict, write or consume anything. Create placement helpers
         * are preflighted separately because their target is not BlockPlaceContext#getClickedPos.
         *
         * Whitelist validation deliberately does NOT happen here. The same ItemStack hooks also run
         * before the clicked block's own interaction; whitelist policy belongs to an attempted
         * non-air write, not to the fact that a player happens to hold a BlockItem.
         */
        if (managedSource && preflightVanillaTarget) {
            BlockPlaceContext placement = new BlockPlaceContext(context);
            if (!ManagedMiniPlacementTargets.isOwnedTarget(
                    context.getLevel(),
                    context.getClickedPos(),
                    placement.getClickedPos())) {
                return InteractionResult.FAIL;
            }
        }

        if (!(context.getLevel() instanceof ServerLevel)) {
            return action.get();
        }

        int countBefore = stack.getCount();
        FrameMaskWriteGuard.beginTrackedItemUse();
        FrameMaskWriteGuard.WriteAttempt attempt;
        InteractionResult result;
        try {
            result = action.get();
        } finally {
            attempt = FrameMaskWriteGuard.finishTrackedItemUse();
        }

        /*
         * Modded BlockItems and placement helpers can consume after a rejected low-level write.
         * A BlockItem used from a managed mini block is successful only if a non-air write was
         * actually accepted by that managed SubLevel. Roll speculative consumption back atomically.
         */
        boolean consumedWithoutManagedPlacement = managedSource
                && stack.getCount() < countBefore
                && !attempt.acceptedNonAirWrite();
        if (attempt.rejectedWithoutPlacement() || consumedWithoutManagedPlacement) {
            if (stack.getCount() < countBefore) {
                stack.setCount(countBefore);
            }
            return InteractionResult.FAIL;
        }
        return result;
    }
}
