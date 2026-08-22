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
import net.minecraft.core.BlockPos;
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

                // Geometry, not replaceability, identifies the neighboring Frame the player is
                // pointing at. A forbidden mini block may make BlockPlaceContext keep the clicked
                // replaceable source cell even though its clicked face leads directly into another
                // Frame. On the client classify that adjacent logical cell explicitly so a different
                // assembly is rejected locally rather than mis-predicted as a macro placement.
                if (context.getLevel().isClientSide) {
                    BlockPos adjacentTarget = context.getClickedPos().relative(context.getClickedFace());
                    ManagedMiniPlacementTargets.ClientFrameTarget clientTarget =
                            ManagedMiniPlacementTargets.resolveClientFrameTarget(
                                    context.getLevel(), context.getClickedPos(), adjacentTarget);
                    if (clientTarget.kind()
                            == ManagedMiniPlacementTargets.ClientTargetKind.OTHER_ASSEMBLY) {
                        if (clientTarget.framePosition() != null) {
                            FramePlacementFeedbackHooks.rejectedPlacement(
                                    context.getLevel(), clientTarget.framePosition());
                        } else {
                            FramePlacementFeedbackHooks.rejectedPlacement(
                                    context.getLevel(), context.getClickedPos());
                        }
                        return InteractionResult.FAIL;
                    }
                }

                // A block that is forbidden inside the mini world may still be a perfectly valid
                // full-size placement on the physical face outside the Frame. Do not let the mini
                // rejection preflight steal that interaction. Same-assembly neighbor Frames are now
                // classified as owned on the client, so they correctly fall through to the rejection
                // pulse instead of taking this macro prediction path.
                InteractionResult outward = AuthoritativePlacementSound.includePlacingPlayer(
                        () -> MicroMacroBoundaryPlacement.route(blockItem, context, placement));
                if (outward != null) {
                    return outward;
                }

                // Only a real mini rejection reaches this point. The dist-safe hook is a no-op on
                // dedicated servers and reveals the hidden owner assembly immediately on the client.
                FramePlacementFeedbackHooks.rejectedPlacement(
                        context.getLevel(), context.getClickedPos());
            }
            return InteractionResult.FAIL;
        }

        if (frameManagedSource && preflightVanillaTarget) {
            BlockPlaceContext placement = new BlockPlaceContext(context);
            BlockPos proposedTarget = placement.getClickedPos();

            if (context.getLevel().isClientSide) {
                ManagedMiniPlacementTargets.ClientFrameTarget clientTarget =
                        ManagedMiniPlacementTargets.resolveClientFrameTarget(
                                context.getLevel(), context.getClickedPos(), proposedTarget);
                if (clientTarget.kind()
                        == ManagedMiniPlacementTargets.ClientTargetKind.OTHER_ASSEMBLY) {
                    // Predict only the interaction/swing. Calling vanilla BlockItem.place here would
                    // write the block into an unowned coordinate of the source child's plot and can
                    // speculatively shrink a survival stack before the server redirects/rejects it.
                    return InteractionResult.SUCCESS;
                }
            }

            if (!ManagedMiniPlacementTargets.isOwnedTarget(
                    context.getLevel(),
                    context.getClickedPos(),
                    proposedTarget)) {
                if (context.getLevel() instanceof ServerLevel serverLevel) {
                    ManagedMiniPlacementTargets.NeighborFrameTarget neighborTarget =
                            ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                                    serverLevel,
                                    context.getClickedPos(),
                                    proposedTarget).orElse(null);
                    if (neighborTarget != null) {
                        return runTrackedCrossFramePlacement(
                                stack,
                                () -> AuthoritativePlacementSound.includePlacingPlayer(
                                        () -> MiniPlacementRouter.placeInNeighborFrame(
                                                blockItem, context, neighborTarget)));
                    }
                }

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

    /**
     * Cross-assembly routing happens before the normal server tracking block above, so install an
     * explicit parent tracker around the nested destination BlockItem use. If the destination route
     * fails before any managed write is accepted, speculative survival consumption is restored. If a
     * modded BlockItem really writes outside the destination FrameMask, recordSuccessfulWrite marks
     * the tracker accepted and the existing overflow transaction owns the material/drop instead of
     * duplicating it with a refund here.
     */
    private static InteractionResult runTrackedCrossFramePlacement(
            ItemStack stack,
            Supplier<InteractionResult> action) {
        int countBefore = stack.getCount();
        FrameMaskWriteGuard.beginTrackedItemUse();
        FrameMaskWriteGuard.WriteAttempt attempt;
        InteractionResult result;
        try {
            result = action.get();
        } finally {
            attempt = FrameMaskWriteGuard.finishTrackedItemUse();
        }

        boolean consumedWithoutAcceptedWrite = stack.getCount() < countBefore
                && !attempt.acceptedNonAirWrite();
        if (attempt.rejectedWithoutPlacement() || consumedWithoutAcceptedWrite) {
            if (stack.getCount() < countBefore) {
                stack.setCount(countBefore);
            }
            return InteractionResult.FAIL;
        }
        return result;
    }
}
