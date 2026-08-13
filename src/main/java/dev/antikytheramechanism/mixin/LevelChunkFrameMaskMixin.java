package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
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
        BlockState previousState = callback.getReturnValue();
        if (previousState == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        FrameMaskWriteGuard.recordSuccessfulWrite(serverLevel, pos, state);
        MiniWorldEnvironment.managedBlockChanged(serverLevel, pos);
        RedstoneBoundaryBridge.notifyParentForManagedWrite(serverLevel, pos);

        SubLevel containing = Sable.HELPER.getContaining(serverLevel, pos);
        if (containing instanceof ServerSubLevel serverSubLevel
                && MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null) {
            // Managed child writes already travelled mini -> host above. Replaying them as if the
            // child plot were a parent space would create a synthetic recursive boundary.
            return;
        }

        // Root writes and writes inside foreign Sable hosts both represent the physical environment
        // immediately outside their Frames. Keep the same signal/topology scheduling semantics in
        // either host space.
        RedstoneBoundaryRefreshScheduler.requestParentWrite(serverLevel, pos, previousState, state);
    }
}
