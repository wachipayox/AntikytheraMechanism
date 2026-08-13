package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.antikytheramechanism.compat.create.MechanismFrameMovementBehaviour", remap = false)
abstract class MechanismFrameMovementUprightMixin {
    private static final String OWNED_STALL = "antikytheramechanism_upright_stall";
    private static final String REPORTED = "antikytheramechanism_upright_reported";
    private static final double EPSILON = 1.0E-5;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$limitToYaw(MovementContext context, CallbackInfo callback) {
        Vec3 up = context.rotation.apply(new Vec3(0.0, 1.0, 0.0));
        boolean upright = Math.abs(up.x) <= EPSILON
                && Math.abs(up.y - 1.0) <= EPSILON
                && Math.abs(up.z) <= EPSILON;
        if (upright) {
            if (context.data.getBoolean(OWNED_STALL)) {
                context.stall = false;
                context.data.remove(OWNED_STALL);
                context.data.remove(REPORTED);
            }
            return;
        }

        context.stall = true;
        context.data.putBoolean(OWNED_STALL, true);
        if (!context.data.getBoolean(REPORTED)) {
            dev.antikytheramechanism.AntikytheraMechanism.LOGGER.warn(
                    "Paused Create contraption containing a Mechanism Frame: pitch/roll is not enabled yet; only upright yaw motion is supported");
            context.data.putBoolean(REPORTED, true);
        }
        callback.cancel();
    }
}
