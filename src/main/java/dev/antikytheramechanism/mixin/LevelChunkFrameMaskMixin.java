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
        boolean selectedSableMovePosition = level instanceof ServerLevel serverLevel
                && SableAssemblyMoveContext.isMovedPosition(serverLevel, pos);
        LevelChunk receiver = (LevelChunk) (Object) this;
        LevelChunk resolved = selectedSableMovePosition
                ? level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                : receiver;

        // Sable 2.0.3's moveBlocks resolves source/target chunks through LevelAccelerator. Its fast
        // ServerChunkCache path can bypass Sable's normal plot routing and return the root-world
        // LevelChunk for a coordinate that is actually owned by a LevelPlot. LevelChunk#setBlockState
        // then masks X/Z and silently mutates the wrong chunk section. Repair only writes at source or
        // target positions explicitly selected by the active Sable move. This covers Frames and the
        // ordinary blocks travelling with them, while unrelated nested writes remain untouched.
        // The nested resolved-chunk call re-enters this injector with receiver == resolved, so the
        // normal FrameMask guard and RETURN-side notifications still run exactly once.
        if (selectedSableMovePosition && receiver != resolved) {
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] REROUTE pos={} state={} receiver={} resolved={}",
                    pos,
                    state,
                    System.identityHashCode(receiver),
                    System.identityHashCode(resolved));
            callback.setReturnValue(resolved.setBlockState(pos, state, moving));
            return;
        }

        BlockState before = requestedFrame ? level.getBlockState(pos) : null;
        boolean allowed = FrameMaskWriteGuard.canWrite(level, pos, state);
        if (requestedFrame) {
            SubLevel containing = Sable.HELPER.getContaining(level, pos);
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] HEAD pos={} before={} requested={} moving={} destinationTransition={} selectedMove={} allowed={} receiver={} resolved={} sameChunk={} containing={}",
                    pos,
                    before,
                    state,
                    moving,
                    destinationTransition,
                    selectedSableMovePosition,
                    allowed,
                    System.identityHashCode(receiver),
                    System.identityHashCode(resolved),
                    receiver == resolved,
                    containing == null ? "ROOT" : containing.getUniqueId());
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
            boolean selectedSableMovePosition = level instanceof ServerLevel serverLevel
                    && SableAssemblyMoveContext.isMovedPosition(serverLevel, pos);
            LevelChunk receiver = (LevelChunk) (Object) this;
            LevelChunk resolved = selectedSableMovePosition
                    ? level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    : receiver;
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] RETURN pos={} previousReturn={} actual={} destinationTransition={} selectedMove={} receiver={} resolved={} sameChunk={}",
                    pos,
                    previousState,
                    level.getBlockState(pos),
                    destinationTransition,
                    selectedSableMovePosition,
                    System.identityHashCode(receiver),
                    System.identityHashCode(resolved),
                    receiver == resolved);
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
