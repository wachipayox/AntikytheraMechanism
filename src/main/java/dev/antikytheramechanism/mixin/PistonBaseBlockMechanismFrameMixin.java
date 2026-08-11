package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Bypasses vanilla's final BlockEntity rejection for our journaled frame only.
 * Every earlier hardness, border, height and push-reaction check remains vanilla.
 */
@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMechanismFrameMixin {
    @Redirect(
            method = "isPushable",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;hasBlockEntity()Z"))
    private static boolean antikytheramechanism$keepOtherBlockEntitiesImmovable(BlockState state) {
        return !state.is(ModRegistries.MECHANISM_FRAME.get()) && state.hasBlockEntity();
    }
}
