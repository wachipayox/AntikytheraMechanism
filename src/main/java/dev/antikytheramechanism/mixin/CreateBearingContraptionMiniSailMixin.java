package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import dev.antikytheramechanism.compat.create.AeronauticsTempSailBridge;
import dev.antikytheramechanism.compat.create.DynamicMiniSailCarrier;
import dev.antikytheramechanism.compat.create.DynamicMiniSailSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a transient derived mini-sail overlay without changing BearingContraption.blocks or native sailBlocks. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.BearingContraption", priority = 1200, remap = false)
abstract class CreateBearingContraptionMiniSailMixin implements DynamicMiniSailCarrier {
    @Shadow protected int sailBlocks;

    @Unique
    private DynamicMiniSailSnapshot antikytheramechanism$miniSails = DynamicMiniSailSnapshot.EMPTY;
    @Unique
    private float antikytheramechanism$originalAeroTempSailStrength;
    @Unique
    private boolean antikytheramechanism$aeroTempAugmented;

    /**
     * Capture before Aeronautics' default-order (1000) callback at the same injection point, then
     * temporarily expose our fractional area through Aero's native temp-sail channel.
     */
    @Inject(
            method = "assemble",
            order = 900,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/bearing/BearingContraption;expandBoundsAroundAxis(Lnet/minecraft/core/Direction$Axis;)V",
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$captureMiniSailsForAssembly(
            Level level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> callback) {
        antikytheramechanism$miniSails = level instanceof ServerLevel serverLevel
                ? DynamicMiniSailSnapshot.capture(serverLevel, (BearingContraption) (Object) this)
                : DynamicMiniSailSnapshot.EMPTY;

        if ((Object) this instanceof AeronauticsTempSailBridge aero) {
            antikytheramechanism$originalAeroTempSailStrength =
                    aero.antikytheramechanism$getAeroTempSailStrength();
            float augmented = antikytheramechanism$originalAeroTempSailStrength
                    + (float) antikytheramechanism$miniSails.miniSailPower();
            aero.antikytheramechanism$setAeroTempSailStrength(augmented);
            antikytheramechanism$aeroTempAugmented = true;
        }
    }

    /**
     * Aeronautics validates at default injector order 1000, then materializes the integer part of its
     * temporary strength into sailBlocks. Run immediately afterwards at the same bytecode point so
     * propellers (which skip Create's later windmill field read) cannot retain that temporary delta.
     */
    @Inject(
            method = "assemble",
            order = 1100,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/bearing/BearingContraption;expandBoundsAroundAxis(Lnet/minecraft/core/Direction$Axis;)V",
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$restoreNativeSailCounterAfterAero(
            Level level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> callback) {
        if (!antikytheramechanism$aeroTempAugmented
                || !((Object) this instanceof AeronauticsTempSailBridge aero)) {
            return;
        }
        float augmented = antikytheramechanism$originalAeroTempSailStrength
                + (float) antikytheramechanism$miniSails.miniSailPower();
        int miniIntegerDelta = (int) augmented - (int) antikytheramechanism$originalAeroTempSailStrength;
        sailBlocks -= miniIntegerDelta;
        aero.antikytheramechanism$setAeroTempSailStrength(antikytheramechanism$originalAeroTempSailStrength);
        antikytheramechanism$aeroTempAugmented = false;
    }

    /**
     * Create's validation compares its integer native count with an integer minimum. Flooring the
     * effective area power is comparison-equivalent while leaving the persisted native field intact.
     */
    @ModifyExpressionValue(
            method = "assemble",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/simibubi/create/content/contraptions/bearing/BearingContraption;sailBlocks:I",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0))
    private int antikytheramechanism$includeMiniPowerInWindmillValidation(int nativeSails) {
        return (int) Math.floor(nativeSails + antikytheramechanism$miniSails.miniSailPower() + 1.0E-9);
    }

    @Override
    public DynamicMiniSailSnapshot antikytheramechanism$getMiniSails() {
        return antikytheramechanism$miniSails;
    }

    @Override
    public void antikytheramechanism$setMiniSails(DynamicMiniSailSnapshot snapshot) {
        antikytheramechanism$miniSails = snapshot == null ? DynamicMiniSailSnapshot.EMPTY : snapshot;
    }
}
