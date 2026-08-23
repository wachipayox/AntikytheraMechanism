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

    @Inject(method = "assemble", at = @At("HEAD"), remap = false)
    private void antikytheramechanism$clearLegacyDisassemblyExceptionBeforeAssembly(CallbackInfo callback) {
        antikytheramechanism$clearLegacyDisassemblyException();
    }

    @Inject(method = "disassemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$requireUprightVoluntaryDisassembly(CallbackInfo callback) {
        if (CreateForcedDisassemblyContext.isForced() || movedContraption == null) {
            antikytheramechanism$clearLegacyDisassemblyException();
            return;
        }

        // lastException is Create's persistent *assembly* failure channel. Putting a rejected
        // disassembly in it makes the bearing HUD incorrectly report "unable to assemble" and the
        // value survives every later no-op assemble attempt. Keep the upright safety rule, but do not
        // poison that persistent HUD state with a disassembly condition.
        antikytheramechanism$clearLegacyDisassemblyException();
        if (!CreateFrameDisassemblyPolicy.canVoluntarilyDisassemble(movedContraption)) {
            callback.cancel();
        }
    }

    private void antikytheramechanism$clearLegacyDisassemblyException() {
        if (!CreateFrameDisassemblyPolicy.isInvalidStaticRotationException(lastException)) {
            return;
        }
        lastException = null;
        ((SyncedBlockEntity) (Object) this).sendData();
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
