package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ClientFreezeWatchdog;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arms the temporary client watchdog immediately before a Mechanism Frame is removed client-side. */
@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameClientFreezeWatchdogMixin {
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void antikytheramechanism$armClientFreezeWatchdog(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston,
            CallbackInfo callback) {
        if (!level.isClientSide || newState.is(state.getBlock())) {
            return;
        }

        ClientFreezeWatchdog.arm(
                Thread.currentThread(),
                "Mechanism Frame removal at " + pos + " in " + level.dimension().location());
    }
}
