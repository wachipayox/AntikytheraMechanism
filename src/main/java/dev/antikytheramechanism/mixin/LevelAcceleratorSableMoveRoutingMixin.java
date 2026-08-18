package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Sable's accelerated chunk lookup on the same routed LevelChunk view as {@link Level#getChunk}
 * while an Antikythera-observed {@code SubLevelAssemblyHelper.moveBlocks} operation is active.
 *
 * <p>Sable 2.0.3's {@link LevelAccelerator} normally asks {@code ServerChunkCache} for the visible
 * full chunk before falling back to {@code Level#getChunk}. For reserved plot coordinates that fast
 * path can return the root-world chunk instead of the {@code LevelPlot} chunk. Reads then see the
 * wrong source state and writes silently mutate the wrong section because {@code LevelChunk} masks
 * X/Z to local coordinates. Only source/target chunks captured by the active atomic move bypass the
 * accelerator fast-path here; all ordinary accelerator traffic keeps Sable's native behavior.</p>
 */
@Mixin(value = LevelAccelerator.class, remap = false)
abstract class LevelAcceleratorSableMoveRoutingMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$routeMovedPlotChunk(
            int chunkX,
            int chunkZ,
            CallbackInfoReturnable<LevelChunk> callback) {
        if (!(level instanceof ServerLevel serverLevel)
                || !SableAssemblyMoveContext.isMovedChunk(serverLevel, chunkX, chunkZ)) {
            return;
        }

        callback.setReturnValue(level.getChunk(chunkX, chunkZ));
    }
}
