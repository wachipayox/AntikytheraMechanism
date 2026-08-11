package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.DispenserWriteContext;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
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
        DispenserWriteContext.enter();
        try {
            return behavior.dispense(source, stack);
        } finally {
            DispenserWriteContext.exit();
        }
    }
}
