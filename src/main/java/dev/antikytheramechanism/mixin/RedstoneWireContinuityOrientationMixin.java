package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.OrientedRedstoneWireContinuity;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryWireContinuity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RedstoneBoundaryWireContinuity.class)
abstract class RedstoneWireContinuityOrientationMixin {
    @Inject(method = "augmentMacroWireSignal", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$rotateContinuity(
            BlockGetter level,
            BlockPos framePosition,
            Direction queryDirection,
            int existingSignal,
            CallbackInfoReturnable<Integer> callback) {
        Integer value = OrientedRedstoneWireContinuity.augment(
                level, framePosition, queryDirection, existingSignal);
        if (value != null) callback.setReturnValue(value);
    }
}
