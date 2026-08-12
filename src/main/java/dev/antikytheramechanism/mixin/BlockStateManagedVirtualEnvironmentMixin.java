package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps the directly adjacent parent-world boundary visible while managed mini blocks execute the
 * ordinary lifecycle callbacks that may validate support after placement.
 *
 * <p>Placement-time virtual reads alone are insufficient. Redstone dust can return AIR from
 * updateShape(DOWN) when its real parent-world floor is hidden, and rails re-check rigid support
 * from neighborChanged whenever a nearby rail changes. Wrapping the BlockState dispatch layer keeps
 * those callbacks vanilla while making the same read-only boundary projection available for the
 * duration of the callback.</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateManagedVirtualEnvironmentMixin {
    @WrapMethod(method = "canSurvive")
    private boolean antikytheramechanism$projectSupportForCanSurvive(
            LevelReader level,
            BlockPos pos,
            Operation<Boolean> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            return original.call(level, pos);
        }
        return MiniWorldEnvironment.withVirtualReads(() -> original.call(level, pos));
    }

    @WrapMethod(method = "updateShape")
    private BlockState antikytheramechanism$projectSupportForUpdateShape(
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos,
            Operation<BlockState> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            return original.call(direction, neighborState, level, pos, neighborPos);
        }
        return MiniWorldEnvironment.withVirtualReads(
                () -> original.call(direction, neighborState, level, pos, neighborPos));
    }

    @WrapMethod(method = "handleNeighborChanged")
    private void antikytheramechanism$projectSupportForNeighborChanged(
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos fromPos,
            boolean isMoving,
            Operation<Void> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            original.call(level, pos, neighborBlock, fromPos, isMoving);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(
                () -> original.call(level, pos, neighborBlock, fromPos, isMoving));
    }

    @WrapMethod(method = "onPlace")
    private void antikytheramechanism$projectSupportForOnPlace(
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean movedByPiston,
            Operation<Void> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            original.call(level, pos, oldState, movedByPiston);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(
                () -> original.call(level, pos, oldState, movedByPiston));
    }

    @WrapMethod(method = "updateIndirectNeighbourShapes(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V")
    private void antikytheramechanism$projectSupportForIndirectShapeUpdates(
            LevelAccessor level,
            BlockPos pos,
            int flags,
            int recursionLeft,
            Operation<Void> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            original.call(level, pos, flags, recursionLeft);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(
                () -> original.call(level, pos, flags, recursionLeft));
    }

    @WrapMethod(method = "tick")
    private void antikytheramechanism$projectSupportForScheduledTick(
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Operation<Void> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(level, pos)) {
            original.call(level, pos, random);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(() -> original.call(level, pos, random));
    }

    @WrapMethod(method = "randomTick")
    private void antikytheramechanism$projectSupportForRandomTick(
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Operation<Void> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(level, pos)) {
            original.call(level, pos, random);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(() -> original.call(level, pos, random));
    }
}
