package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.OrientedRedstoneBoundary;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RedstoneBoundaryBridge.class)
abstract class RedstoneBoundaryOrientationMixin {
    @Inject(method = "projectedParentSignal", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$projected(
            ServerLevel level, BlockPos pos, Direction direction, boolean direct,
            CallbackInfoReturnable<Integer> callback) {
        Integer value = OrientedRedstoneBoundary.projected(level, pos, direction, direct);
        if (value != null) callback.setReturnValue(value);
    }

    @Inject(method = "frameOutputSignal", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$output(
            BlockGetter level, BlockPos pos, Direction direction, boolean direct,
            CallbackInfoReturnable<Integer> callback) {
        Integer value = OrientedRedstoneBoundary.output(level, pos, direction, direct);
        if (value != null) callback.setReturnValue(value);
    }

    @Inject(method = "frameCanConnectRedstone", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$connect(
            BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction,
            CallbackInfoReturnable<Boolean> callback) {
        Boolean value = OrientedRedstoneBoundary.connects(level, pos, direction);
        if (value != null) callback.setReturnValue(value);
    }
}
