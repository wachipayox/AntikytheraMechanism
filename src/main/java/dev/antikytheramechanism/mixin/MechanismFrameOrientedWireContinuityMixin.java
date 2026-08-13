package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.sublevel.OrientedRedstoneWireContinuity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameOrientedWireContinuityMixin {
    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$augmentRotatedDust(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Integer> callback) {
        Integer value = OrientedRedstoneWireContinuity.augment(
                level, position, direction, callback.getReturnValueI());
        if (value != null) callback.setReturnValue(value);
    }
}
