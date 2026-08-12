package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Evaluates projected parent-world redstone at its real parent BlockPos. */
@Mixin(SignalGetter.class)
interface SignalGetterRedstoneBoundaryMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$bridgeProjectedWeakSignal(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Integer> callback) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        Integer signal = RedstoneBoundaryBridge.projectedParentSignal(serverLevel, pos, direction, false);
        if (signal != null) {
            callback.setReturnValue(signal);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$bridgeProjectedDirectSignal(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Integer> callback) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        Integer signal = RedstoneBoundaryBridge.projectedParentSignal(serverLevel, pos, direction, true);
        if (signal != null) {
            callback.setReturnValue(signal);
        }
    }
}
