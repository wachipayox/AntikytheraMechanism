package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples only vanilla's persisted carrier progress; mini states never move. */
@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityAssemblyMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private static void antikytheramechanism$followAssemblyPose(
            Level level,
            BlockPos position,
            BlockState state,
            PistonMovingBlockEntity carrier,
            CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager.get(serverLevel)
                    .onPistonCarrierTick(serverLevel, position, carrier);
        }
    }

    @Inject(method = "finalTick", at = @At("TAIL"))
    private void antikytheramechanism$finishInterruptedCarrier(CallbackInfo callback) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager.get(serverLevel)
                    .onPistonCarrierTick(serverLevel, self.getBlockPos(), self);
        }
    }
}
