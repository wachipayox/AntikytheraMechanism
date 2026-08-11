package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockItem.class)
abstract class BlockItemMiniEnvironmentMixin {
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
