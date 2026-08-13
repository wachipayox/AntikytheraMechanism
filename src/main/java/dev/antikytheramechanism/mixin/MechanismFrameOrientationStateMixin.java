package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameOrientationStateMixin {
    @Shadow protected abstract void registerDefaultState(BlockState state);

    @Inject(method = "createBlockStateDefinition", at = @At("HEAD"))
    private void antikytheramechanism$addFacing(
            StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void antikytheramechanism$defaultFacing(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties properties, CallbackInfo ci) {
        registerDefaultState(((MechanismFrameBlock) (Object) this).defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$inheritFacing(
            BlockPlaceContext context, CallbackInfoReturnable<BlockState> callback) {
        BlockState result = callback.getReturnValue();
        if (result == null) return;
        Direction facing = Direction.NORTH;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(direction));
            if (neighbor.is((MechanismFrameBlock) (Object) this)
                    && neighbor.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING);
                break;
            }
        }
        callback.setReturnValue(result.setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
    }

    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$separateIncompatibleFrames(
            BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos,
            CallbackInfoReturnable<BlockState> callback) {
        if (!neighborState.is((MechanismFrameBlock) (Object) this)
                || neighborState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING)) return;
        callback.setReturnValue(callback.getReturnValue().setValue(connection(direction), false));
    }

    @Inject(method = "canStickTo", at = @At("RETURN"), cancellable = true)
    private void antikytheramechanism$stickOnlyMatching(
            BlockState state, BlockState other, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && other.is((MechanismFrameBlock) (Object) this)
                && other.getValue(BlockStateProperties.HORIZONTAL_FACING)
                != state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            callback.setReturnValue(false);
        }
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty connection(Direction direction) {
        return switch (direction) {
            case DOWN -> MechanismFrameBlock.CONNECTED_DOWN;
            case UP -> MechanismFrameBlock.CONNECTED_UP;
            case NORTH -> MechanismFrameBlock.CONNECTED_NORTH;
            case SOUTH -> MechanismFrameBlock.CONNECTED_SOUTH;
            case WEST -> MechanismFrameBlock.CONNECTED_WEST;
            case EAST -> MechanismFrameBlock.CONNECTED_EAST;
        };
    }
}
