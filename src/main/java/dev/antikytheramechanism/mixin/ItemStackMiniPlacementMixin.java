package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.interaction.AuthoritativePlacementSound;
import dev.antikytheramechanism.interaction.MicroMacroBoundaryPlacement;
import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
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
            // MiniPlacementRouter is server-authoritative when it handles the click: its client path
            // intentionally consumes prediction without mutating the client plot. Mark only the routed
            // attempt, not the ordinary fallback, so vanilla placements keep their normal sound path.
            InteractionResult routed = AuthoritativePlacementSound.includePlacingPlayer(
                    () -> MiniPlacementRouter.route(blockItem, context));
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
         * ItemStack#useOn is reached only after the clicked block's own interaction had a chance to
         * consume the click. At this placement stage it is safe (and desirable for client prediction)
         * to reject nested Frames and non-whitelisted BlockItems before BlockItem creates a ghost
         * placement. onItemUseFirst deliberately skips this check; FrameMaskWriteGuard remains the
         * server-side backstop for custom first-use helpers that attempt a real write.
         */
        if (managedSource
                && preflightVanillaTarget
                && (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                        || !MiniaturizableRegistry.isAllowed(blockItem.getBlock()))) {
            // A block used on the outward face of a mini boundary is no longer a mini placement.
            // Let the macro router decide before applying the mini whitelist; full-size blocks are
            // allowed to leave the Frame even when that block is not miniaturizable.
            BlockPlaceContext placement = new BlockPlaceContext(context);
            if (!ManagedMiniPlacementTargets.isOwnedTarget(
                    context.getLevel(), context.getClickedPos(), placement.getClickedPos())) {
                InteractionResult outward = AuthoritativePlacementSound.includePlacingPlayer(
                        () -> MicroMacroBoundaryPlacement.route(blockItem, context, placement));
                if (outward != null) {
                    return outward;
                }
            }
            return InteractionResult.FAIL;
        }

        /*
         * A normal BlockItem used on a mini support can produce a relative BlockPlaceContext target
         * just outside the 2x2x2 FrameMask. That normally means invalid mini placement, except when
         * the player actually clicked the outward face of an edge mini block. In that case route the
         * placement back into the Frame's macro host, after the clicked mini block already had vanilla
         * priority to consume the interaction.
         */
        if (managedSource && preflightVanillaTarget) {
            BlockPlaceContext placement = new BlockPlaceContext(context);
            if (!ManagedMiniPlacementTargets.isOwnedTarget(
                    context.getLevel(),
                    context.getClickedPos(),
                    placement.getClickedPos())) {
                InteractionResult outward = AuthoritativePlacementSound.includePlacingPlayer(
                        () -> MicroMacroBoundaryPlacement.route(blockItem, context, placement));
                if (outward != null) {
                    return outward;
                }
                return InteractionResult.FAIL;
            }
        }

        if (!(context.getLevel() instanceof ServerLevel)) {
            InteractionResult result = action.get();
            if (managedSource
                    && preflightVanillaTarget
                    && result.consumesAction()
                    && !result.shouldSwing()) {
                return InteractionResult.SUCCESS;
            }
            return result;
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
