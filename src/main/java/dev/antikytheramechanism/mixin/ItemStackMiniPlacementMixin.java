package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
abstract class ItemStackMiniPlacementMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$routeMiniPlacement(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.getItem() instanceof BlockItem blockItem) {
            InteractionResult routed = MiniPlacementRouter.route(blockItem, context);
            if (routed != null) {
                callback.setReturnValue(routed);
            }
        }
    }
}
