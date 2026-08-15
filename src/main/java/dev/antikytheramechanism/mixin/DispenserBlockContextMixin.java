package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.DispenserWriteContext;
import dev.antikytheramechanism.sublevel.MiniFluidPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DispenserBlock.class)
abstract class DispenserBlockContextMixin {
    @Redirect(
            method = "dispenseFrom",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack antikytheramechanism$guardDispenserWrites(
            DispenseItemBehavior behavior,
            BlockSource source,
            ItemStack stack) {
        /*
         * BucketItem#emptyContents itself is guarded as the authoritative placement path. Preflight
         * here as well so a denied bucket remains in the dispenser instead of falling through to
         * vanilla's "could not empty -> eject the full bucket as an item" behavior.
         */
        if (stack.getItem() instanceof BucketItem) {
            var contained = FluidUtil.getFluidContained(stack);
            if (contained.isPresent() && !contained.get().isEmpty()) {
                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                if (!MiniFluidPolicy.allowsBucketFluid(source.level(), target, contained.get().getFluid())) {
                    return stack;
                }
            }
        }

        DispenserWriteContext.enter();
        try {
            return behavior.dispense(source, stack);
        } finally {
            DispenserWriteContext.exit();
        }
    }
}
