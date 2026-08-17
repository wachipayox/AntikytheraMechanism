package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.createmod.catnip.placement.PlacementOffset;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
 *
 * <p>A helper target just outside one Frame can also physically land inside a different neighboring
 * Frame (for example a diagonal cog suggestion or a separately yawed Frame). That case is not a
 * FrameMask escape: the write is redirected into the destination Frame's own managed SubLevel and
 * the transformed state is rebased into that Frame's logical yaw. Arbitrary macro-world targets
 * remain rejected exactly as before.</p>
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

        PlacementOffset placement = (PlacementOffset) (Object) this;
        BlockPos proposedTarget = placement.getBlockPos();

        /*
         * The client has no authoritative FrameMask graph. Its existing projection check accepts a
         * helper target only when the source child projects that cell into a real physical Frame,
         * which is exactly what lets Create keep rendering/accepting the ghost for a neighbor Frame
         * while continuing to reject a proposal into ordinary macro space.
         */
        if (!(world instanceof ServerLevel serverLevel)) {
            if (!ManagedMiniPlacementTargets.isOwnedTarget(world, source, proposedTarget)) {
                return ItemInteractionResult.FAIL;
            }
            return original.call(world, blockItem, player, hand, ray);
        }

        ItemStack held = player.getItemInHand(hand);
        int countBefore = held.getCount();
        BlockPos committedTarget;
        ItemInteractionResult result;

        if (ManagedMiniPlacementTargets.isOwnedTarget(serverLevel, source, proposedTarget)) {
            committedTarget = proposedTarget;
            result = MiniWorldEnvironment.withVirtualReads(
                    () -> original.call(world, blockItem, player, hand, ray));
        } else {
            ManagedMiniPlacementTargets.NeighborFrameTarget neighborTarget =
                    ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                            serverLevel, source, proposedTarget).orElse(null);
            if (neighborTarget == null) {
                return ItemInteractionResult.FAIL;
            }
            committedTarget = neighborTarget.destinationGlobalPosition();
            result = MiniWorldEnvironment.withVirtualReads(() -> placeInNeighborFrame(
                    placement,
                    serverLevel,
                    blockItem,
                    player,
                    hand,
                    ray,
                    neighborTarget));
        }

        // Catnip's NeoForge hook does not inspect setBlockAndUpdate's boolean return. A helper can
        // therefore report SUCCESS even if a lower-level guard rejected/restored the write. Treat
        // that as an atomic failure and undo any speculative survival consumption.
        if (result == ItemInteractionResult.SUCCESS
                && !serverLevel.getBlockState(committedTarget).is(blockItem.getBlock())) {
            if (held.getCount() < countBefore) {
                held.setCount(countBefore);
            }
            return ItemInteractionResult.FAIL;
        }

        return result;
    }

    /**
     * Catnip's normal placeInWorld cannot be delegated here because its private target remains a
     * coordinate in the source assembly's plot. Mirror Catnip's public placement contract while
     * substituting the authoritative destination plot coordinate for every world-side operation.
     */
    private static ItemInteractionResult placeInNeighborFrame(
            PlacementOffset placement,
            ServerLevel world,
            BlockItem blockItem,
            Player player,
            InteractionHand hand,
            BlockHitResult ray,
            ManagedMiniPlacementTargets.NeighborFrameTarget neighborTarget) {
        if (!placement.isSuccessful()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockPos destination = neighborTarget.destinationGlobalPosition();
        if (!world.getBlockState(destination).canBeReplaced()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        UseOnContext context = new UseOnContext(player, hand, ray);
        ItemStack stackBefore = player.getItemInHand(hand).copy();
        if (!world.mayInteract(player, destination)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockState state = placement.getTransform()
                .apply(blockItem.getBlock().defaultBlockState())
                .rotate(neighborTarget.stateRotation());
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            FluidState fluidState = world.getFluidState(destination);
            state = state.setValue(
                    BlockStateProperties.WATERLOGGED,
                    fluidState.getType() == Fluids.WATER);
        }

        if (CatnipServices.HOOKS.playerPlaceSingleBlock(player, world, destination, state)) {
            return ItemInteractionResult.FAIL;
        }

        /*
         * NeoForge's Catnip hook ignores Level#setBlockAndUpdate's boolean. Check immediately before
         * sound/stat/advancement side effects as well as in the outer atomic guard, so a FrameMask
         * rejection can never masquerade as a successful cross-Frame helper placement.
         */
        BlockState newState = world.getBlockState(destination);
        if (!newState.is(blockItem.getBlock())) {
            return ItemInteractionResult.FAIL;
        }

        SoundType soundType = newState.getSoundType();
        world.playSound(
                null,
                destination,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F);
        world.gameEvent(GameEvent.BLOCK_PLACE, destination, GameEvent.Context.of(player, newState));

        player.awardStat(Stats.ITEM_USED.get(blockItem));
        newState.getBlock().setPlacedBy(world, destination, newState, player, stackBefore);

        if (player instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, destination, context.getItemInHand());
        }
        if (!player.isCreative()) {
            context.getItemInHand().shrink(1);
        }

        return ItemInteractionResult.SUCCESS;
    }
}
