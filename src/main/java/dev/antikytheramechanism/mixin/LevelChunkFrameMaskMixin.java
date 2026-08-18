package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
        boolean requestedFrame = state.is(ModRegistries.MECHANISM_FRAME.get());
        boolean destinationTransition = level instanceof ServerLevel serverLevel
                && SableFrameRelocationService.isDestinationTransition(serverLevel, pos);
        BlockState before = requestedFrame ? level.getBlockState(pos) : null;
        boolean allowed = FrameMaskWriteGuard.canWrite(level, pos, state);
        if (requestedFrame) {
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] HEAD pos={} before={} requested={} moving={} destinationTransition={} allowed={}",
                    pos, before, state, moving, destinationTransition, allowed);
        }
        if (!allowed) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void antikytheramechanism$refreshManagedWorldState(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        BlockState previousState = callback.getReturnValue();
        if (state.is(ModRegistries.MECHANISM_FRAME.get())) {
            boolean destinationTransition = level instanceof ServerLevel serverLevel
                    && SableFrameRelocationService.isDestinationTransition(serverLevel, pos);
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] RETURN pos={} previousReturn={} actual={} destinationTransition={}",
                    pos, previousState, level.getBlockState(pos), destinationTransition);
        }
        if (previousState == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        FrameMaskWriteGuard.recordSuccessfulWrite(serverLevel, pos, state);
        MiniWorldEnvironment.managedBlockChanged(serverLevel, pos);

        if (!SableAssemblyMoveContext.deferManagedParentNotification(serverLevel, pos)) {
            RedstoneBoundaryBridge.notifyParentForManagedWrite(serverLevel, pos);
        }

        SubLevel containing = Sable.HELPER.getContaining(serverLevel, pos);
        if (containing instanceof ServerSubLevel serverSubLevel
                && MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null) {
            return;
        }

        RedstoneBoundaryRefreshScheduler.requestParentWrite(serverLevel, pos, previousState, state);
    }
}
