package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.simulated_team.simulated.util.assembly.SimAssemblyContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Adds Antikythera's exact-source boundary to Simulated's otherwise unchanged movement rules. */
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
}
