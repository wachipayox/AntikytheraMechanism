package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.frame.FramePlacementFeedbackHooks;
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
            if (DetachedMiniPhysicsSubLevelService.isDetachedPosition(
                    context.getLevel(), context.getClickedPos())) {
                return original.call(context);
            }

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

        if (restrictedMiniSource
                && preflightVanillaTarget
                && (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                        || !MiniaturizableRegistry.isAllowed(blockItem.getBlock()))) {
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
                // This path executes on the prediction client as well as the server. The dist-safe
                // hook is a no-op on dedicated servers and immediately reveals the hidden owner Frame
                // on the client, before any ghost placement can be created.
                FramePlacementFeedbackHooks.rejectedPlacement(
                        context.getLevel(), context.getClickedPos());
            }
            return InteractionResult.FAIL;
        }

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

        boolean compensateForeignHostedSound = frameManagedSource
                && AuthoritativePlacementSound.shouldCompensateForeignHostedManagedPlacement(
                        context.getLevel(), context.getClickedPos());

        int countBefore = stack.getCount();
        FrameMaskWriteGuard.beginTrackedItemUse();
        FrameMaskWriteGuard.WriteAttempt attempt;
        InteractionResult result;
        try {
            result = compensateForeignHostedSound
                    ? AuthoritativePlacementSound.includePlacingPlayer(action)
                    : action.get();
        } finally {
            attempt = FrameMaskWriteGuard.finishTrackedItemUse();
        }

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
