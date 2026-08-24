package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement helpers run before ItemStack.useOn and can consume a click on a macro support block before
 * Antikythera gets a chance to route that click into the adjacent Mechanism Frame. When the vanilla
 * placement context unambiguously points from a macro support into a same-host Frame, yield the
 * item-first stage so the existing MiniPlacementRouter remains the sole placement authority.
 */
@Mixin(ItemStack.class)
abstract class ItemStackParentSupportPlacementMixin {
    @Inject(method = "onItemUseFirst", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$yieldPlacementHelperToFrameSupport(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        ItemStack self = (ItemStack) (Object) this;
        if (!(self.getItem() instanceof BlockItem blockItem)
                || MiniPlacementRouter.isBypassing()
                || blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                || !MiniaturizableRegistry.isAllowed(blockItem.getBlock())) {
            return;
        }

        BlockPos clicked = context.getClickedPos();
        if (MiniWorldEnvironment.isManagedMiniPosition(context.getLevel(), clicked)
                || DetachedMiniPhysicsSubLevelService.isDetachedPosition(context.getLevel(), clicked)
                || context.getLevel().getBlockState(clicked).is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        BlockPos target = new BlockPlaceContext(context).getClickedPos();
        if (target.equals(clicked)
                || !context.getLevel().getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())
                || !MechanismAssemblyHost.samePhysicalHost(context.getLevel(), clicked, target)) {
            return;
        }

        callback.setReturnValue(InteractionResult.PASS);
    }
}
