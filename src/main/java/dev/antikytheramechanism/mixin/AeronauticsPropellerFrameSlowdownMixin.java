package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.compat.create.CreateFrameDisassemblyPolicy;
import dev.simulated_team.simulated.api.BearingSlowdownController;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Makes Aeronautics stop Frame-carrying propellers only at a statically upright phase. */
@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity", remap = false)
abstract class AeronauticsPropellerFrameSlowdownMixin {
    @Redirect(
            method = "startDisassemblySlowdown",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/api/BearingSlowdownController;generate(FFFLnet/minecraft/core/Direction;Lcom/simibubi/create/content/contraptions/Contraption;)V"),
            remap = false)
    private void antikytheramechanism$forceFullTurnForFrames(
            BearingSlowdownController controller,
            float maxTime,
            float initialAngle,
            float initialVelocity,
            Direction facingDirection,
            Contraption attachedContraption) {
        // The bearing-facing axis is already expressed in the coordinate system of the level that
        // owns this bearing. On a Sable sublevel, Y therefore means sublevel-UP, exactly as required.
        if (facingDirection.getAxis() != Direction.Axis.Y
                && CreateFrameDisassemblyPolicy.containsMechanismFrame(attachedContraption)) {
            controller.generate(
                    maxTime,
                    initialAngle,
                    initialVelocity,
                    BearingSlowdownController.ContraptionSymmetry.NONE);
            return;
        }
        controller.generate(maxTime, initialAngle, initialVelocity, facingDirection, attachedContraption);
    }
}
