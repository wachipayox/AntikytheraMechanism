package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameFaceSupport;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

/**
 * Keeps the directly adjacent parent-world boundary visible while managed mini blocks execute the
 * ordinary lifecycle callbacks that may validate support or query redstone after placement.
 *
 * <p>Placement-time virtual reads alone are insufficient. Redstone dust can return AIR from
 * updateShape(DOWN) when its real parent-world floor is hidden, rails re-check rigid support from
 * neighborChanged, and pistons re-check power from triggerEvent immediately before actually moving.
 * Wrapping the BlockState dispatch layer keeps those callbacks vanilla while making the same
 * read-only boundary projection available for the duration of the callback.</p>
 *
 * <p>Rails use a stricter view: real parent blocks remain visible as support, but parent rails are
 * hidden from topology resolution. Vanilla RailState assumes discovered rails can be mutated in the
 * same Level coordinate space, which is false for our read-only projected shell.</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateManagedVirtualEnvironmentMixin {
    @Unique
    private <T> T antikytheramechanism$withLifecycleEnvironment(Supplier<T> action) {
        BlockState state = (BlockState) (Object) this;
        return state.is(BlockTags.RAILS)
                ? MiniWorldEnvironment.withVirtualReadsExcludingExternalRails(action)
                : MiniWorldEnvironment.withVirtualReads(action);
    }

    @Unique
    private void antikytheramechanism$withLifecycleEnvironment(Runnable action) {
        antikytheramechanism$withLifecycleEnvironment(() -> {
            action.run();
            return null;
        });
    }

    @WrapMethod(method = "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z")
    private boolean antikytheramechanism$projectMiniFaceSupport(
            BlockGetter level,
            BlockPos pos,
            Direction direction,
            SupportType supportType,
            Operation<Boolean> original) {
        BlockState state = (BlockState) (Object) this;
        if (!state.is(ModRegistries.MECHANISM_FRAME.get())) {
            return original.call(level, pos, direction, supportType);
        }

        Boolean projected = FrameFaceSupport.query(level, pos, direction, supportType);
        return projected != null
                ? projected
                : original.call(level, pos, direction, supportType);
    }

    @WrapMethod(method = "canSurvive")
    private boolean antikytheramechanism$projectSupportForCanSurvive(
            LevelReader level,
            BlockPos pos,
            Operation<Boolean> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            return original.call(level, pos);
        }
        return antikytheramechanism$withLifecycleEnvironment(() -> original.call(level, pos));
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
        return antikytheramechanism$withLifecycleEnvironment(
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
        antikytheramechanism$withLifecycleEnvironment(
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
        antikytheramechanism$withLifecycleEnvironment(
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
        antikytheramechanism$withLifecycleEnvironment(
                () -> original.call(level, pos, flags, recursionLeft));
    }

    @WrapMethod(method = "triggerEvent")
    private boolean antikytheramechanism$projectBoundaryForBlockEvent(
            Level level,
            BlockPos pos,
            int id,
            int param,
            Operation<Boolean> original) {
        if (!(level instanceof ServerLevel serverLevel)
                || !MiniWorldEnvironment.shouldUseVirtualReads(serverLevel, pos)) {
            return original.call(level, pos, id, param);
        }
        return antikytheramechanism$withLifecycleEnvironment(
                () -> original.call(level, pos, id, param));
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
        antikytheramechanism$withLifecycleEnvironment(() -> original.call(level, pos, random));
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
        antikytheramechanism$withLifecycleEnvironment(() -> original.call(level, pos, random));
    }
}
