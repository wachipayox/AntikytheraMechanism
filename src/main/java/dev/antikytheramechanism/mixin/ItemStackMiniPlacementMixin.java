package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.interaction.AuthoritativePlacementSound;
import dev.antikytheramechanism.interaction.MicroMacroBoundaryPlacement;
import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
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
            // Detached physics bodies are ordinary Sable worlds: they keep Antikythera policy but
            // must not enter the Frame-only 2x2x2 ray/placement router or its macro-boundary escape.
            if (DetachedMiniPhysicsSubLevelService.isDetachedPosition(
                    context.getLevel(), context.getClickedPos())) {
                return original.call(context);
            }

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
        boolean frameManagedSource = MiniWorldEnvironment.isManagedMiniPosition(
                context.getLevel(), context.getClickedPos());
        boolean detachedSource = DetachedMiniPhysicsSubLevelService.isDetachedPosition(
                context.getLevel(), context.getClickedPos());
        boolean restrictedMiniSource = frameManagedSource || detachedSource;

        /*
         * ItemStack#useOn is reached only after the clicked block's own interaction had a chance to
         * consume the click. At this placement stage it is safe (and desirable for client prediction)
         * to reject nested Frames and non-whitelisted BlockItems before BlockItem creates a ghost
         * placement. onItemUseFirst deliberately skips this check; FrameMaskWriteGuard remains the
         * server-side backstop for custom first-use helpers that attempt a real write.
         */
        if (restrictedMiniSource
                && preflightVanillaTarget
                && (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                        || !MiniaturizableRegistry.isAllowed(blockItem.getBlock()))) {
            // Only Frame children have a semantic outward face that can route a full-size placement
            // back into the macro host. A detached body is already free and remains mini-only.
            if (frameManagedSource) {
                BlockPlaceContext placement = new BlockPlaceContext(context);
                if (!ManagedMiniPlacementTargets.isOwnedTarget(
                        context.getLevel(), context.getClickedPos(), placement.getClickedPos())) {
                    InteractionResult outward = AuthoritativePlacementSound.includePlacingPlayer(
                            () -> MicroMacroBoundaryPlacement.route(blockItem, context, placement));
                    if (outward != null) {
                        return outward;
                    }
                }
            }
            return InteractionResult.FAIL;
        }

        /*
         * A normal BlockItem used on a mini support can produce a relative BlockPlaceContext target
         * just outside the 2x2x2 FrameMask. That normally means invalid mini placement, except when
         * the player actually clicked the outward face of an edge mini block. Detached bodies have
         * no FrameMask and intentionally use normal Sable local placement instead.
         */
        if (frameManagedSource && preflightVanillaTarget) {
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
            if (frameManagedSource
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
         * A BlockItem used from either Antikythera mini-world type is successful only if a real
         * non-air write was accepted. Roll speculative consumption back atomically.
         */
        boolean consumedWithoutManagedPlacement = restrictedMiniSource
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
