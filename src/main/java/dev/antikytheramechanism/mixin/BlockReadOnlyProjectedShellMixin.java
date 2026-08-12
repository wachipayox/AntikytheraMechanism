package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Final no-drop barrier for Antikythera's read-only projected parent-world shell. */
@Mixin(Block.class)
abstract class BlockReadOnlyProjectedShellMixin {
    @Inject(
            method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void antikytheramechanism$skipProjectedShellDrop(
            Level level,
            BlockPos pos,
            ItemStack stack,
            CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && MiniWorldEnvironment.virtualBlockState(serverLevel, pos) != null) {
            callback.cancel();
        }
    }
}
