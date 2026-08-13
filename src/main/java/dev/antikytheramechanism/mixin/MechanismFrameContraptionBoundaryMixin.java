package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
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

@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameContraptionBoundaryMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$disconnectWeak(
            BlockState state, BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> callback) {
        if (inTransit(level, pos)) callback.setReturnValue(0);
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$disconnectDirect(
            BlockState state, BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> callback) {
        if (inTransit(level, pos)) callback.setReturnValue(0);
    }

    @Inject(method = "canConnectRedstone", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$disconnectWire(
            BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction,
            CallbackInfoReturnable<Boolean> callback) {
        if (inTransit(level, pos)) callback.setReturnValue(false);
    }

    private static boolean inTransit(BlockGetter level, BlockPos position) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        return manager.getAssemblyAt(position)
                .map(assembly -> manager.pendingContraptionMove(assembly.id()).isPresent())
                .orElse(false);
    }
}
