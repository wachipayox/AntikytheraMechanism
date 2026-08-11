package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** Invisible service-shell cog; small and large registrations share this implementation. */
public final class InternalCogPortBlock extends KineticBlock
        implements IBE<InternalTransmissionPortBlockEntity>, ICogWheel {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    private final boolean large;

    public InternalCogPortBlock(boolean large, Properties properties) {
        super(properties);
        this.large = large;
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean isLargeCog() {
        return large;
    }

    @Override
    public boolean isSmallCog() {
        return !large;
    }

    @Override
    public boolean isDedicatedCogWheel() {
        return true;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
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
