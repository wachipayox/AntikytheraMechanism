package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.compat.simulated.SimulatedFrameAttachmentPolicy;
import dev.simulated_team.simulated.index.SimBlockMovementChecks;
import dev.simulated_team.simulated.service.SimAssemblyService;
import dev.simulated_team.simulated.util.assembly.SimAssemblyContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds Antikythera's exact-source boundary and Frame-assembly identity rules to Simulated. */
@Mixin(value = SimAssemblyContraption.class, remap = false)
abstract class SimAssemblyContraptionMiniBoundaryMixin {
    @WrapMethod(method = "movementAllowed")
    private boolean antikytheramechanism$stayInsideSourceMiniWorld(
            BlockState state,
            Level level,
            BlockPos position,
            Operation<Boolean> original) {
        if (!original.call(state, level, position)) {
            return false;
        }
        return !MiniPhysicsAssemblyContext.isActive()
                || MiniPhysicsAssemblyContext.allowsCandidate(level, position, state);
    }

    /**
     * Simulated has a position-aware attachment extension for all 18 BFS offsets, including edge
     * diagonals. For Frame pairs, replace proximity/block-state attachment with the persisted
     * MechanismAssembly identity. This keeps a complete logical Frame structure implicit while an
     * unrelated touching Frame is not captured merely because it is nearby.
     */
    @WrapOperation(
            method = "moveBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/index/SimBlockMovementChecks;checkIsBlockAttachedTowards(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean antikytheramechanism$scopeFrameAttachmentToLogicalAssembly(
            BlockState state,
            Level level,
            BlockPos position,
            BlockPos direction,
            Operation<Boolean> original) {
        Boolean override = SimulatedFrameAttachmentPolicy.attachmentOverride(
                state, level, position, direction);
        return override != null
                ? override
                : original.call(state, level, position, direction);
    }

    /**
     * NeoForge's generic BlockState#canStickTo cannot see positions, so it cannot distinguish two
     * independent Frame assemblies that happen to share a facing. Disable only that generic
     * Frame-to-Frame term inside Simulated's BFS. Explicit super/honey glue is evaluated separately
     * by Simulated and therefore remains able to join independent assemblies intentionally.
     */
    @WrapOperation(
            method = "moveBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/service/SimAssemblyService;canStickTo(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean antikytheramechanism$disableGenericFramePairStickiness(
            SimAssemblyService service,
            BlockState first,
            BlockState second,
            Operation<Boolean> original) {
        if (!SimulatedFrameAttachmentPolicy.useGenericStickiness(first, second)) {
            return false;
        }
        return original.call(service, first, second);
    }
}
