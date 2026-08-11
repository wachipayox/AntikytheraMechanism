package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Parent-world side of the Create kinetic bridge.
 *
 * <p>{@link #FACING} points from the box towards its adjacent Mechanism Frame. The other five
 * faces expose equivalent 1:1 shaft connections. The mini-face quadrants are converted to stable
 * service-shell ports by {@link TransmissionLinkCoordinator}; no player block is replaced by an
 * implementation proxy.</p>
 */
public final class TransmissionBoxBlock extends KineticBlock
        implements IBE<TransmissionBoxBlockEntity> {
    public static final DirectionProperty FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
    public static final IntegerProperty FACE_ROLL = IntegerProperty.create("face_roll", 0, 3);
    public static final BooleanProperty DIAGONAL_B = BooleanProperty.create("diagonal_b");
    public static final IntegerProperty COVER_MASK = IntegerProperty.create("cover_mask", 0, 15);

    private final TransmissionBoxKind kind;

    public TransmissionBoxBlock(TransmissionBoxKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(FACE_ROLL, 0)
                .setValue(DIAGONAL_B, false)
                .setValue(COVER_MASK, 0));
    }

    public TransmissionBoxKind kind() {
        return kind;
    }

    public static TransmissionFaceOrientation orientation(BlockState state) {
        return new TransmissionFaceOrientation(state.getValue(FACING), state.getValue(FACE_ROLL));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        // The five macro shafts are one rigid node. This nominal axis is used only by generic
        // Create rendering/placement helpers; hasShaftTowards is the authoritative topology.
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face != state.getValue(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction miniFace = context.getClickedFace().getOpposite();
        return defaultBlockState()
                .setValue(FACING, miniFace)
                .setValue(FACE_ROLL, 0);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(FACE_ROLL, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(FACE_ROLL, 0);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return super.getRotatedBlockState(state, targetedFace).setValue(FACE_ROLL, 0);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (context.getClickedFace() == state.getValue(FACING)) {
            TransmissionBoxBlockEntity box = getBlockEntity(level, pos);
            if (box == null) {
                return InteractionResult.FAIL;
            }
            int quadrant = quadrant(state, pos, context.getClickLocation());
            if (kind.supportsCovers() && (box.coverMask() & 1 << quadrant) != 0) {
                if (!level.isClientSide) {
                    box.setCoverMask(box.coverMask() & ~(1 << quadrant));
                    Player player = context.getPlayer();
                    if (player != null && !player.isCreative()) {
                        player.getInventory().placeItemBackInInventory(
                                new ItemStack(CreateTransmissionRegistries.MINI_SHAFT_COVER.get()));
                    }
                    TransmissionLinkCoordinator.reconcile((ServerLevel) level, pos);
                }
                IWrenchable.playRotateSound(level, pos);
                return InteractionResult.SUCCESS;
            }
            if (kind.usesDiagonalSelection()) {
                if (!level.isClientSide) {
                    BlockState toggled = state.cycle(DIAGONAL_B);
                    KineticBlockEntity.switchToBlockState(level, pos, toggled);
                    TransmissionLinkCoordinator.reconcile((ServerLevel) level, pos);
                }
                IWrenchable.playRotateSound(level, pos);
                return InteractionResult.SUCCESS;
            }
        }
        return super.onWrenched(state, context);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!kind.supportsCovers()
                || !stack.is(CreateTransmissionRegistries.MINI_SHAFT_COVER.get())
                || hit.getDirection() != state.getValue(FACING)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        TransmissionBoxBlockEntity box = getBlockEntity(level, pos);
        if (box == null) {
            return ItemInteractionResult.FAIL;
        }
        int quadrant = quadrant(state, pos, hit.getLocation());
        if ((box.coverMask() & 1 << quadrant) != 0) {
            return ItemInteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            box.setCoverMask(box.coverMask() | 1 << quadrant);
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            TransmissionLinkCoordinator.reconcile((ServerLevel) level, pos);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (newState.getBlock() != state.getBlock() && level instanceof ServerLevel serverLevel) {
            TransmissionBoxBlockEntity box = getBlockEntity(level, pos);
            if (box != null) {
                TransmissionLinkCoordinator.remove(serverLevel, pos, box);
                if (!isMoving) {
                    for (int bit = 0; bit < Integer.bitCount(box.coverMask()); bit++) {
                        Block.popResource(level, pos, new ItemStack(CreateTransmissionRegistries.MINI_SHAFT_COVER.get()));
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FACE_ROLL, DIAGONAL_B, COVER_MASK);
    }

    @Override
    public Class<TransmissionBoxBlockEntity> getBlockEntityClass() {
        return TransmissionBoxBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TransmissionBoxBlockEntity> getBlockEntityType() {
        return CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get();
    }

    private TransmissionBoxBlockEntity getBlockEntity(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box ? box : null;
    }

    public static BlockState withCoverMask(BlockState state, int mask) {
        return state.hasProperty(COVER_MASK) ? state.setValue(COVER_MASK, mask & 0xF) : state;
    }

    static int quadrant(BlockState state, BlockPos pos, Vec3 hitLocation) {
        TransmissionFaceOrientation orientation = orientation(state);
        Vec3 relative = hitLocation.subtract(Vec3.atCenterOf(pos));
        int u = dot(relative, orientation.u()) >= 0.0 ? 1 : 0;
        int v = dot(relative, orientation.v()) >= 0.0 ? 1 : 0;
        return u | v << 1;
    }

    private static double dot(Vec3 vector, Direction direction) {
        return vector.x * direction.getStepX()
                + vector.y * direction.getStepY()
                + vector.z * direction.getStepZ();
    }
}
