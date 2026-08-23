package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents historical/stale FrameMask entries from repainting a physical Frame that is currently
 * owned by another assembly.
 *
 * <p>Create extraction deliberately allows a moving assembly to retain historical source positions
 * after live ownership has been released. Presentation synchronization must therefore follow the
 * authoritative frame index, not merely membership in {@link MechanismAssembly#frames()}.</p>
 */
@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyPresentationOwnershipMixin {
    @Inject(method = "syncFrameBlockEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$onlySynchronizeActiveOwner(
            ServerLevel level,
            BlockPos pos,
            MechanismAssembly assembly,
            CallbackInfo callback) {
        MechanismAssemblyManager manager = (MechanismAssemblyManager) (Object) this;
        MechanismAssembly activeOwner = manager.getAssemblyAt(pos).orElse(null);
        if (activeOwner == null || !activeOwner.id().equals(assembly.id())) {
            callback.cancel();
        }
    }
}
