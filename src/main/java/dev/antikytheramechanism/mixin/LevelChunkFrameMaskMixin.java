package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
import dev.ryanhcode.sable.Sable;
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
    private void antikytheramechanism$refreshManagedWorldState(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        FrameMaskWriteGuard.recordSuccessfulWrite(serverLevel, pos, state);
        MiniWorldEnvironment.managedBlockChanged(serverLevel, pos);
        RedstoneBoundaryBridge.notifyParentForManagedWrite(serverLevel, pos);

        if (Sable.HELPER.getContaining(serverLevel, pos) != null) {
            return;
        }

        // The exact macro position that changed is known here. Reconcile only that projected
        // boundary in the current tick instead of turning every dust POWER write into a full six-face
        // Frame replay. Generic neighbour callbacks still use MechanismFrameBlock's deferred full
        // refresh, and same-position re-entry is deferred by the scheduler for shape oscillations.
        RedstoneBoundaryRefreshScheduler.requestParentWrite(serverLevel, pos);
    }
}
