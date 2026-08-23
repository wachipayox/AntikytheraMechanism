package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import dev.antikytheramechanism.compat.create.CreateForcedDisassemblyContext;
import dev.antikytheramechanism.compat.create.CreateFrameDisassemblyPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives voluntary and forced bearing disassembly deliberately different policies. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity", remap = false)
abstract class CreateMechanicalBearingDisassemblyMixin {
    @Shadow protected ControlledContraptionEntity movedContraption;
    @Shadow protected AssemblyException lastException;

    @Inject(method = "disassemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$requireUprightVoluntaryDisassembly(CallbackInfo callback) {
        if (CreateForcedDisassemblyContext.isForced() || movedContraption == null) {
            return;
        }
        if (CreateFrameDisassemblyPolicy.canVoluntarilyDisassemble(movedContraption)) {
            if (CreateFrameDisassemblyPolicy.isInvalidStaticRotationException(lastException)) {
                lastException = null;
                ((SyncedBlockEntity) (Object) this).sendData();
            }
            return;
        }

        lastException = CreateFrameDisassemblyPolicy.invalidStaticRotationException();
        ((SyncedBlockEntity) (Object) this).sendData();
        callback.cancel();
    }

    /**
     * SmartBlockEntity.remove() is also used when the controller is physically destroyed. That is a
     * forced teardown, not a player's request to leave the machine at its current orientation.
     */
    @WrapMethod(method = "remove", remap = false)
    private void antikytheramechanism$markControllerRemovalAsForced(Operation<Void> original) {
        CreateForcedDisassemblyContext.runForced(() -> original.call());
    }
}
