package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
abstract class LevelMiniWorldEnvironmentMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$projectParentEnvironment(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> callback) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            BlockState virtualState = MiniWorldEnvironment.virtualBlockState(serverLevel, pos);
            if (virtualState != null) {
                callback.setReturnValue(virtualState);
            }
        }
    }
}
