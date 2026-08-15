package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniFluidPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
abstract class BucketItemMiniFluidPolicyMixin {
    @Shadow @Final private Fluid content;

    /*
     * NeoForge 1.21.1 routes both normal BucketItem#use and vanilla dispenser bucket behavior through
     * this ItemStack-sensitive overload. Cancel before LiquidBlockContainer#placeLiquid so denied
     * water cannot waterlog a block, and before any source-fluid block, sound, game event or item
     * consumption is produced.
     */
    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void antikytheramechanism$rejectDeniedMiniFluid(
            @Nullable Player player,
            Level level,
            BlockPos position,
            @Nullable BlockHitResult hitResult,
            @Nullable ItemStack container,
            CallbackInfoReturnable<Boolean> callback) {
        if (content != Fluids.EMPTY && !MiniFluidPolicy.allowsBucketFluid(level, position, content)) {
            callback.setReturnValue(false);
        }
    }
}
