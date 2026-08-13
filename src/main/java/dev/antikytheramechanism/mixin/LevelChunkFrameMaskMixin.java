package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

        // Parent-world state changes beside a Frame normally need to cross the virtual boundary in
        // this same server tick, just like an ordinary contiguous redstone circuit. The refresh
        // scheduler executes the first request synchronously and only defers if the exact same Frame
        // is requested recursively while that pass is still active (the trapdoor/shape-oscillation
        // case that used to freeze the server).
        antikytheramechanism$refreshAdjacentFrames(serverLevel, pos);
    }

    private static void antikytheramechanism$refreshAdjacentFrames(
            ServerLevel level,
            BlockPos parentPosition) {
        if (Sable.HELPER.getContaining(level, parentPosition) != null) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = parentPosition.relative(directionToFrame);
            if (!level.hasChunkAt(framePosition)
                    || manager.getAssemblyAt(framePosition).isEmpty()
                    || !level.getChunkAt(framePosition)
                            .getBlockState(framePosition)
                            .is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            RedstoneBoundaryRefreshScheduler.request(level, framePosition);
        }
    }
}
