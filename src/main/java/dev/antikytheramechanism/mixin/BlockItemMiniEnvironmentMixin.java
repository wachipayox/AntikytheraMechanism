package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.interaction.ManagedPlacementCollisionPolicy;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
abstract class BlockItemMiniEnvironmentMixin {
    /**
     * Do not call BlockPlaceContext#canPlace for placements whose only cross-level overlap is an
     * Antikythera SubLevel. Sable injects its scale-unaware 1x1x1 OBB veto inside that method; by
     * intercepting the vanilla BlockItem call one level above it, injection order cannot make Sable
     * win before our correction. All later BlockItem validation remains intact.
     */
    @WrapOperation(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/context/BlockPlaceContext;canPlace()Z"))
    private boolean antikytheramechanism$useVanillaPlacementEligibility(
            BlockPlaceContext context,
            Operation<Boolean> original) {
        if (!ManagedPlacementCollisionPolicy.shouldUseVanillaContextCanPlace(context)) {
            return original.call(context);
        }
        return ManagedPlacementCollisionPolicy.vanillaContextCanPlace(context);
    }

    @WrapMethod(method = "getPlacementState")
    private @Nullable BlockState antikytheramechanism$readVirtualSupportForPlacementState(
            BlockPlaceContext context,
            Operation<BlockState> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(context.getLevel(), context.getClickedPos())) {
            return original.call(context);
        }
        return MiniWorldEnvironment.withVirtualReads(() -> original.call(context));
    }

    @WrapMethod(method = "canPlace")
    private boolean antikytheramechanism$readVirtualSupportForSurvival(
            BlockPlaceContext context,
            BlockState state,
            Operation<Boolean> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(context.getLevel(), context.getClickedPos())) {
            return original.call(context, state);
        }
        return MiniWorldEnvironment.withVirtualReads(() -> original.call(context, state));
    }
}
