package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.DeferredFrameRemovalLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps destructive evacuation out of the ServerLevel#setBlock/onRemove call stack. */
@Mixin(MechanismFrameBlock.class)
public abstract class MechanismFrameDeferredRemovalMixin {
    @Redirect(
            method = "onRemove",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/antikytheramechanism/assembly/MechanismAssemblyManager;onFrameRemoved(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V"))
    private void antikytheramechanism$finishOrDeferRemoval(
            MechanismAssemblyManager manager,
            ServerLevel level,
            BlockPos framePosition) {
        if (manager.isFrameEvacuated(framePosition)) {
            manager.onFrameRemoved(level, framePosition);
            return;
        }
        DeferredFrameRemovalLifecycle.defer(level, framePosition);
    }
}
