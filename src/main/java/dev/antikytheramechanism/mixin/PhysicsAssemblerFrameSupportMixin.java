package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.compat.simulated.PhysicsAssemblerFrameSupport;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Simulated's tiny-surface attachment semantics when a supporting Frame shell is HIDDEN. */
@Mixin(value = PhysicsAssemblerBlock.class, remap = false)
abstract class PhysicsAssemblerFrameSupportMixin {
    @Inject(method = "canAttach", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$projectHiddenFrameMiniSupport(
            LevelReader level,
            BlockPos assemblerPosition,
            Direction directionToSupport,
            CallbackInfoReturnable<Boolean> callback) {
        BlockPos supportPosition = assemblerPosition.relative(directionToSupport);
        BlockState supportState = level.getBlockState(supportPosition);
        if (!supportState.is(ModRegistries.MECHANISM_FRAME.get())
                || supportState.getValue(MechanismFrameBlock.SHELL_MODE) != FrameShellMode.HIDDEN) {
            return;
        }

        // Simulated asks the support block for the face pointing back toward the assembler.
        Boolean projected = PhysicsAssemblerFrameSupport.query(
                level,
                supportPosition,
                directionToSupport.getOpposite());
        if (projected != null) {
            callback.setReturnValue(projected);
        }
    }
}
