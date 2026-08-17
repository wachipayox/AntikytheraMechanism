package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateMiniKineticLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

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
            finishCarrierUpdate(serverLevel, position, carrier);
        }
    }

    @Inject(method = "finalTick", at = @At("TAIL"))
    private void antikytheramechanism$finishInterruptedCarrier(CallbackInfo callback) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel serverLevel) {
            finishCarrierUpdate(serverLevel, self.getBlockPos(), self);
        }
    }

    private static void finishCarrierUpdate(
            ServerLevel level,
            BlockPos carrierPosition,
            PistonMovingBlockEntity carrier) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        manager.onPistonCarrierTick(level, carrierPosition, carrier);

        /*
         * The manager keeps frameIndex on the source side until every carrier has settled. Therefore
         * resolving an assembly at the carrier position after reconciliation succeeds only once this
         * position is a committed destination Frame. Re-advertise then, not while the piston is in
         * flight; duplicate callbacks from a multi-Frame move collapse into the lifecycle's Set queue.
         */
        MechanismAssembly settled = manager.getAssemblyAt(carrierPosition).orElse(null);
        if (settled != null && manager.pendingPistonMove(settled.id()).isEmpty()) {
            CreateMiniKineticLifecycle.scheduleAfterPhysicalRelocation(level, Set.of(settled.id()));
        }
    }
}
