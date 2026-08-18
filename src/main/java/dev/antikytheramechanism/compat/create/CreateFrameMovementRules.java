package dev.antikytheramechanism.compat.create;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import dev.antikytheramechanism.assembly.FrameAssemblyAttachment;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/** Conservative Create collection rules for complete frame assemblies. */
final class CreateFrameMovementRules {
    private CreateFrameMovementRules() {
    }

    static BlockMovementChecks.CheckResult movementAllowed(
            Block frameBlock,
            BlockState state,
            Level level,
            BlockPos position) {
        if (!state.is(frameBlock)) {
            return BlockMovementChecks.CheckResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return BlockMovementChecks.CheckResult.PASS;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(position).orElse(null);
        if (assembly == null
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || manager.isContentRecoveryLocked(assembly.id())) {
            return BlockMovementChecks.CheckResult.FAIL;
        }
        for (BlockPos framePosition : assembly.frames()) {
            if (!serverLevel.isLoaded(framePosition)
                    || !serverLevel.getBlockState(framePosition).is(frameBlock)) {
                return BlockMovementChecks.CheckResult.FAIL;
            }
            BlockEntity blockEntity = serverLevel.getBlockEntity(framePosition);
            if (!(blockEntity instanceof MechanismFrameBlockEntity frame)) {
                return BlockMovementChecks.CheckResult.FAIL;
            }
            UUID owner = frame.getAssemblyId();
            if (!assembly.id().equals(owner)) {
                return BlockMovementChecks.CheckResult.FAIL;
            }
        }
        return BlockMovementChecks.CheckResult.SUCCESS;
    }

    static BlockMovementChecks.CheckResult attached(
            Block frameBlock,
            BlockState state,
            Level level,
            BlockPos position,
            net.minecraft.core.Direction direction) {
        BlockPos neighborPosition = position.relative(direction);
        if (!state.is(frameBlock) || !level.getBlockState(neighborPosition).is(frameBlock)) {
            return BlockMovementChecks.CheckResult.PASS;
        }
        // Implicit Frame attachment is structural, not geometric. Only members of the same persisted
        // MechanismAssembly are forced into Create's frontier. Distinct touching assemblies return
        // PASS so ordinary Create glue can still join them explicitly.
        return FrameAssemblyAttachment.sameAssembly(level, position, neighborPosition)
                ? BlockMovementChecks.CheckResult.SUCCESS
                : BlockMovementChecks.CheckResult.PASS;
    }
}
