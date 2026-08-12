package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
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
        return runTrackedBlockUse(self, blockItem, context, () -> {
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
        // Create's cog/shaft placement guides execute here rather than in BlockItem#useOn.
        return runTrackedBlockUse(self, blockItem, context, () -> original.call(context));
    }

    private static InteractionResult runTrackedBlockUse(
            ItemStack stack,
            BlockItem blockItem,
            UseOnContext context,
            Supplier<InteractionResult> action) {
        boolean managedSource = MiniWorldEnvironment.isManagedMiniPosition(
                context.getLevel(), context.getClickedPos());
        if (managedSource
                && (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                        || !MiniaturizableRegistry.isAllowed(blockItem.getBlock()))) {
            return InteractionResult.FAIL;
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
         * Some Create placement helpers calculate an offset from a valid mini cog/shaft and then
         * consume the source stack even when the destination lies outside the FrameMask. Depending
         * on where that synthetic target lands, the rejected setBlock can occur just beyond Sable's
         * current containing bounds and therefore cannot always be observed as a rejected managed
         * write. For a BlockItem used from a managed mini block, a real successful placement must
         * produce an accepted non-air write in that managed SubLevel. If the count dropped without
         * one, the consumption was speculative and must be rolled back server-side.
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
