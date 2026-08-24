package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Configurable one-node gearbox bridging ordinary Create shafts and half-scale Frame kinetics. */
public final class TransmissionBoxBlock extends RotatedPillarKineticBlock
        implements IBE<TransmissionBoxBlockEntity> {

    public TransmissionBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, Direction.Axis.Y);
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis() == state.getValue(AXIS)) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box
                && box.faceMode(face) == TransmissionBoxFaceMode.MACRO;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box)) {
            return InteractionResult.PASS;
        }

        BlockHitResult hit = new BlockHitResult(
                context.getClickLocation(),
                context.getClickedFace(),
                pos,
                false);
        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolve(hit, box);

        if (target.kind() == TransmissionBoxHitTarget.Kind.NONE) {
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        switch (target.kind()) {
            case CORNER -> {
                box.cycleCorner(target.corner());
                IWrenchable.playRotateSound(level, pos);
            }
            case FACE -> {
                if (box.cycleFace(target.face())) {
                    IWrenchable.playRotateSound(level, pos);
                }
            }
            case ROTATE -> {
                // Rotation is deliberately available only through a CLOSED region. Configuration
                // rotates physically with the box, including an axial click where AXIS itself stays
                // unchanged but the four lateral face assignments turn around that axis.
                BlockState rotated = getRotatedBlockState(state, context.getClickedFace());
                box.beginTopologyMutation();
                box.rotateConfiguration(context.getClickedFace().getAxis());
                KineticBlockEntity.switchToBlockState(level, pos, rotated);
                box.finishTopologyMutation();
                IWrenchable.playRotateSound(level, pos);
            }
            case NONE -> {
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        // A structural-axis change necessarily changes which four physical faces can carry ports.
        return oldState == newState;
    }

    @Override
    public Class<TransmissionBoxBlockEntity> getBlockEntityClass() {
        return TransmissionBoxBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TransmissionBoxBlockEntity> getBlockEntityType() {
        return CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get();
    }
}
