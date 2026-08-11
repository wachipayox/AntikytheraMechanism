package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Invisible service-shell shaft; never registered with a BlockItem. */
public final class InternalShaftPortBlock extends KineticBlock
        implements IBE<InternalTransmissionPortBlockEntity> {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public InternalShaftPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == getRotationAxis(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public Class<InternalTransmissionPortBlockEntity> getBlockEntityClass() {
        return InternalTransmissionPortBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends InternalTransmissionPortBlockEntity> getBlockEntityType() {
        return CreateTransmissionRegistries.INTERNAL_TRANSMISSION_PORT_BLOCK_ENTITY.get();
    }
}
