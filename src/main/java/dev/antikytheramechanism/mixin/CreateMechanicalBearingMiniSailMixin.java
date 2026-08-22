package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import dev.antikytheramechanism.compat.create.CreateMiniSailOverlayManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lightweight bearing heartbeat; discovery itself remains dirty/event-driven. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity", remap = false)
abstract class CreateMechanicalBearingMiniSailMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void antikytheramechanism$refreshDirtyMiniSails(CallbackInfo callback) {
        CreateMiniSailOverlayManager.observe((MechanicalBearingBlockEntity) (Object) this);
    }

    @Inject(method = "disassemble", at = @At("TAIL"))
    private void antikytheramechanism$forgetMiniSailsAfterDisassembly(CallbackInfo callback) {
        CreateMiniSailOverlayManager.forget((MechanicalBearingBlockEntity) (Object) this);
    }
}
