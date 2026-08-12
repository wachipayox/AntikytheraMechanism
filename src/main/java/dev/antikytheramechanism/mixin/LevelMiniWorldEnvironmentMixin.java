package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
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

    /**
     * A projected shell state is a read-only view of a real parent-world block. Never allow vanilla
     * lifecycle code to destroy that virtual coordinate: Level#destroyBlock would otherwise obtain
     * the projected state, generate its drops, and only then fail the write at the FrameMask guard.
     */
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$refuseProjectedShellDestroy(
            BlockPos pos,
            boolean dropBlock,
            @Nullable Entity entity,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ServerLevel serverLevel
                && MiniWorldEnvironment.virtualBlockState(serverLevel, pos) != null) {
            callback.setReturnValue(false);
        }
    }
}
