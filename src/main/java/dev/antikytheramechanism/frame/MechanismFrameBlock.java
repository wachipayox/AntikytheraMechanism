package dev.antikytheramechanism.frame;

import com.mojang.serialization.MapCodec;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.server.ServerFreezeWatchdog;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class MechanismFrameBlock extends BaseEntityBlock implements EntityBlock {
    public static final MapCodec<MechanismFrameBlock> CODEC = simpleCodec(MechanismFrameBlock::new);
    public static final BooleanProperty EMPTY = BooleanProperty.create("empty");
    public static final BooleanProperty CONNECTED_DOWN = BooleanProperty.create("connected_down");
    public static final BooleanProperty CONNECTED_UP = BooleanProperty.create("connected_up");
    public static final BooleanProperty CONNECTED_NORTH = BooleanProperty.create("connected_north");
    public static final BooleanProperty CONNECTED_SOUTH = BooleanProperty.create("connected_south");
    public static final BooleanProperty CONNECTED_WEST = BooleanProperty.create("connected_west");
    public static final BooleanProperty CONNECTED_EAST = BooleanProperty.create("connected_east");

    private static final Map<Direction, BooleanProperty> CONNECTION_PROPERTIES = new EnumMap<>(Direction.class);
    private static final double BAR = 2.0;

    static {
        CONNECTION_PROPERTIES.put(Direction.DOWN, CONNECTED_DOWN);
        CONNECTION_PROPERTIES.put(Direction.UP, CONNECTED_UP);
        CONNECTION_PROPERTIES.put(Direction.NORTH, CONNECTED_NORTH);
        CONNECTION_PROPERTIES.put(Direction.SOUTH, CONNECTED_SOUTH);
        CONNECTION_PROPERTIES.put(Direction.WEST, CONNECTED_WEST);
        CONNECTION_PROPERTIES.put(Direction.EAST, CONNECTED_EAST);
    }

    public MechanismFrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(EMPTY, true)
                .setValue(CONNECTED_DOWN, false)
                .setValue(CONNECTED_UP, false)
                .setValue(CONNECTED_NORTH, false)
                .setValue(CONNECTED_SOUTH, false)
                .setValue(CONNECTED_WEST, false)
                .setValue(CONNECTED_EAST, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(
                EMPTY,
                CONNECTED_DOWN,
                CONNECTED_UP,
                CONNECTED_NORTH,
                CONNECTED_SOUTH,
                CONNECTED_WEST,
                CONNECTED_EAST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) {
            state = state.setValue(
                    CONNECTION_PROPERTIES.get(direction),
                    context.getLevel().getBlockState(context.getClickedPos().relative(direction)).is(this));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        return state.setValue(CONNECTION_PROPERTIES.get(direction), neighborState.is(this));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Selection is always the actual frame cage. Mini placement is routed from the real
        // clicked block/face; no invisible 2x2 placement panels are added to the hitbox.
        return cageShape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cageShape(state);
    }

    /**
     * The physical Frame is the read-only macro-world endpoint of the redstone bridge. It does not
     * contain or copy any mini block; it only exposes the strongest signal on the corresponding
     * 2x2 mini face.
     */
    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return RedstoneBoundaryBridge.frameOutputSignal(level, pos, direction, false);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return RedstoneBoundaryBridge.frameOutputSignal(level, pos, direction, true);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MechanismFrameBlockEntity(pos, state);
    }

    @Override
    public boolean isStickyBlock(BlockState state) {
        return true;
    }

    @Override
    public boolean canStickTo(BlockState state, BlockState other) {
        return other.is(this);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this) && level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (!manager.isPhysicalRelocationTransition(pos)) {
                ServerFreezeWatchdog.arm(
                        Thread.currentThread(),
                        "Mechanism Frame placement at " + pos + " in " + serverLevel.dimension().location());
                manager.onFramePlaced(serverLevel, pos);
            }
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!newState.is(this) && level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            boolean relocation = manager.isPhysicalRelocationTransition(pos);
            dev.antikytheramechanism.AntikytheraMechanism.LOGGER.debug(
                    "Frame onRemove at {} movedByPiston={} relocationJournal={}", pos, movedByPiston, relocation);
            if (!relocation) {
                manager.onFrameRemoved(serverLevel, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (!manager.isPhysicalRelocationTransition(pos)) {
                manager.evacuateFrame(
                        serverLevel,
                        pos,
                        FrameEvacuationService.Cause.player(player, player.getMainHandItem()));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public boolean onDestroyedByPlayer(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            boolean willHarvest,
            FluidState fluid) {
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (manager.isFrameLifecycleLocked(pos)) {
                return false;
            }
            if (!manager.isFrameEvacuated(pos)
                    && !manager.evacuateFrame(
                            serverLevel,
                            pos,
                            FrameEvacuationService.Cause.player(player, player.getMainHandItem()))) {
                return false;
            }
            return level.removeBlock(pos, false);
        }
        return level.setBlock(pos, fluid.createLegacyBlock(), 11);
    }

    @Override
    protected void onExplosionHit(
            BlockState state,
            Level level,
            BlockPos pos,
            Explosion explosion,
            BiConsumer<ItemStack, BlockPos> dropConsumer) {
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (manager.isFrameLifecycleLocked(pos)
                    || !manager.evacuateFrame(serverLevel, pos, FrameEvacuationService.Cause.explosion())) {
                return;
            }
        }
        super.onExplosionHit(state, level, pos, explosion, dropConsumer);
    }

    private static VoxelShape cageShape(BlockState state) {
        VoxelShape result = Shapes.empty();
        for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(state, ySide) && !connected(state, zSide)) {
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(0, y0, z0, 16, y0 + BAR, z0 + BAR));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(state, xSide) && !connected(state, zSide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(x0, 0, z0, x0 + BAR, 16, z0 + BAR));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
                if (!connected(state, xSide) && !connected(state, ySide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - BAR;
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(x0, y0, 0, x0 + BAR, y0 + BAR, 16));
                }
            }
        }
        return result;
    }

    private static boolean connected(BlockState state, Direction direction) {
        return state.getValue(CONNECTION_PROPERTIES.get(direction));
    }
}
