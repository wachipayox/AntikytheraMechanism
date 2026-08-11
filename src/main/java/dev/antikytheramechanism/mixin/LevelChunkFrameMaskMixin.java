package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkFrameMaskMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$enforceFrameMask(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        if (!FrameMaskWriteGuard.canWrite(level, pos, state)) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void antikytheramechanism$refreshVirtualEnvironment(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() != null && level instanceof ServerLevel serverLevel) {
            MiniWorldEnvironment.parentBlockChanged(serverLevel, pos);
        }
    }
}
