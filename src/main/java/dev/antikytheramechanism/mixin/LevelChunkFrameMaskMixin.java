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
    private static final ThreadLocal<Boolean> REROUTED_OUTER_RETURN = new ThreadLocal<>();

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
        LevelChunk receiver = (LevelChunk) (Object) this;
        LevelChunk resolved = requestedFrame && destinationTransition
                ? level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                : receiver;

        // Sable 2.0.3 can invoke LevelChunk#setBlockState on the source/receiver chunk while passing
        // a destination position that belongs to a different chunk in the same ServerLevel. Vanilla
        // LevelChunk#setBlockState does not validate the position's X/Z against the receiver; it masks
        // those coordinates and mutates the wrong chunk section. During an already-journaled Frame
        // relocation, route that one synchronous destination write through the chunk that actually
        // owns the destination position. The nested call performs the ordinary FrameMask check and
        // all successful-write notifications; the cancelled outer invocation must therefore skip its
        // RETURN hook to avoid replaying those side effects.
        if (requestedFrame && destinationTransition && receiver != resolved) {
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] REROUTE pos={} receiver={} resolved={}",
                    pos,
                    System.identityHashCode(receiver),
                    System.identityHashCode(resolved));
            BlockState previousState = resolved.setBlockState(pos, state, moving);
            REROUTED_OUTER_RETURN.set(Boolean.TRUE);
            callback.setReturnValue(previousState);
            return;
        }

        BlockState before = requestedFrame ? level.getBlockState(pos) : null;
        boolean allowed = FrameMaskWriteGuard.canWrite(level, pos, state);
        if (requestedFrame) {
            SubLevel containing = Sable.HELPER.getContaining(level, pos);
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] HEAD pos={} before={} requested={} moving={} destinationTransition={} allowed={} receiver={} resolved={} sameChunk={} containing={}",
                    pos,
                    before,
                    state,
                    moving,
                    destinationTransition,
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
        if (Boolean.TRUE.equals(REROUTED_OUTER_RETURN.get())) {
            REROUTED_OUTER_RETURN.remove();
            return;
        }

        BlockState previousState = callback.getReturnValue();
        if (state.is(ModRegistries.MECHANISM_FRAME.get())) {
            boolean destinationTransition = level instanceof ServerLevel serverLevel
                    && SableFrameRelocationService.isDestinationTransition(serverLevel, pos);
            LevelChunk receiver = (LevelChunk) (Object) this;
            LevelChunk resolved = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            AntikytheraMechanism.LOGGER.error(
                    "[HOST-WRITE-DIAG] RETURN pos={} previousReturn={} actual={} destinationTransition={} receiver={} resolved={} sameChunk={}",
                    pos,
                    previousState,
                    level.getBlockState(pos),
                    destinationTransition,
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
