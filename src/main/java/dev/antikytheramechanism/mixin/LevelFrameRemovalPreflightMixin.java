package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.FrameEvacuationService;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Evacuates a Frame before {@link Level#setBlock} enters the underlying chunk write.
 *
 * <p>Sable 2.0.3 tracks the position being written by {@code LevelChunk#setBlockState} in one mutable
 * field on the chunk. Evacuating mini contents from Antikythera's lower LevelChunk preflight can
 * synchronously refresh the Frame's EMPTY state, causing a nested write to the same chunk. The nested
 * Sable invocation clears that field before the outer write reaches Sable's section-write wrapper.
 * Running the same fail-closed evacuation one layer earlier lets every evacuation/Frame refresh finish
 * before the outer LevelChunk write begins. The existing low-level FrameMask guard remains the
 * authority for writes that bypass Level#setBlock and observes the Frame as already evacuated here.</p>
 */
@Mixin(Level.class)
abstract class LevelFrameRemovalPreflightMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void antikytheramechanism$evacuateFrameBeforeChunkWrite(
            BlockPos position,
            BlockState newState,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> callback) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)
                || newState.is(ModRegistries.MECHANISM_FRAME.get())
                || !serverLevel.getBlockState(position).is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        if (manager.isPhysicalRelocationTransition(position) || manager.isFrameEvacuated(position)) {
            return;
        }

        if (!manager.evacuateFrame(
                serverLevel,
                position,
                FrameEvacuationService.Cause.generic())) {
            AntikytheraMechanism.LOGGER.error(
                    "Rejected replacement of Mechanism Frame {} because its mini payload could not be evacuated safely before the chunk write",
                    position);
            callback.setReturnValue(false);
        }
    }
}
