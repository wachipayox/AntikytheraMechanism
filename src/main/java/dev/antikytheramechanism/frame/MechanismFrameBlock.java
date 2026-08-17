package dev.antikytheramechanism.frame;

import com.mojang.serialization.MapCodec;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.client.ClientFreezeWatchdog;
import dev.antikytheramechanism.server.ServerFreezeWatchdog;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryBridge;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryWireContinuity;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class MechanismFrameBlock extends BaseEntityBlock
        implements EntityBlock, BlockSubLevelAssemblyListener {
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
    private static final int CONNECTION_MASK_COUNT = 1 << Direction.values().length;

    static {
        CONNECTION_PROPERTIES.put(Direction.DOWN, CONNECTED_DOWN);
        CONNECTION_PROPERTIES.put(Direction.UP, CONNECTED_UP);
        CONNECTION_PROPERTIES.put(Direction.NORTH, CONNECTED_NORTH);
        CONNECTION_PROPERTIES.put(Direction.SOUTH, CONNECTED_SOUTH);
        CONNECTION_PROPERTIES.put(Direction.WEST, CONNECTED_WEST);
        CONNECTION_PROPERTIES.put(Direction.EAST, CONNECTED_EAST);
    }

    /**
     * Frame geometry depends only on six connection booleans, so there are just 64 possible cages.
     * Building a cage requires several Shapes.or operations; doing that from collision queries made
     * terrain debris above a Frame spend most of its client tick in VoxelShape mergers. Precompute all
     * variants once and make both selection and collision lookups allocation/merge free at runtime.
     */
    private static final VoxelShape[] CAGE_SHAPES = buildCageShapes();

    public MechanismFrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(EMPTY, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
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
                BlockStateProperties.HORIZONTAL_FACING,
                CONNECTED_DOWN,
                CONNECTED_UP,
                CONNECTED_NORTH,
                CONNECTED_SOUTH,
                CONNECTED_WEST,
                CONNECTED_EAST);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(
                BlockStateProperties.HORIZONTAL_FACING,
                rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos target = context.getClickedPos();
        if (!MechanismAssemblyHost.canHostFrame(context.getLevel(), target)) {
            return null;
        }

        BlockState state = defaultBlockState();
        Direction facing = Direction.NORTH;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = context.getLevel().getBlockState(target.relative(direction));
            if (neighbor.is(this) && neighbor.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING);
                break;
            }
        }
        state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        for (Direction direction : Direction.values()) {
            state = state.setValue(
                    CONNECTION_PROPERTIES.get(direction),
                    connectsTo(state, context.getLevel().getBlockState(target.relative(direction))));
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
        return state.setValue(CONNECTION_PROPERTIES.get(direction), connectsTo(state, neighborState));
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos fromPos,
            boolean isMoving) {
        super.neighborChanged(state, level, pos, neighborBlock, fromPos, isMoving);
        if (level instanceof ServerLevel serverLevel) {
            RedstoneBoundaryRefreshScheduler.requestFromNeighbor(serverLevel, pos, fromPos);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        RedstoneBoundaryRefreshScheduler.runScheduled(level, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cageShape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cageShape(state);
    }

    /**
     * Vanilla fluid placement treats every non-solid block as replaceable by a fluid even when the
     * block is not waterloggable. A Frame is intentionally a non-full cage, but liquid must never be
     * allowed to replace the structural block and bypass its transactional evacuation lifecycle.
     */
    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (bridgeSuppressed(level, pos)) return 0;
        int bridged = RedstoneBoundaryBridge.frameOutputSignal(level, pos, direction, false);
        return RedstoneBoundaryWireContinuity.augmentMacroWireSignal(level, pos, direction, bridged);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (bridgeSuppressed(level, pos)) return 0;
        return RedstoneBoundaryBridge.frameOutputSignal(level, pos, direction, true);
    }

    @Override
    public boolean canConnectRedstone(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            @Nullable Direction direction) {
        if (bridgeSuppressed(level, pos)) return false;
        return RedstoneBoundaryBridge.frameCanConnectRedstone(state, level, pos, direction);
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
        return connectsTo(state, other);
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
        if (level.isClientSide && !newState.is(this)) {
            ClientFreezeWatchdog.arm(
                    Thread.currentThread(),
                    "Mechanism Frame removal at " + pos + " in " + level.dimension().location());
        }
        if (!newState.is(this) && level instanceof ServerLevel serverLevel) {
            RedstoneBoundaryRefreshScheduler.discard(serverLevel, pos);
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

    @Override
    public void beforeMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockState newState,
            BlockPos oldPos,
            BlockPos newPos) {
        SableFrameRelocationService.beforeMove(originLevel, resultingLevel, oldPos, newPos);
    }

    @Override
    public void afterMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockState newState,
            BlockPos oldPos,
            BlockPos newPos) {
        SableFrameRelocationService.afterMove(originLevel, resultingLevel, oldPos, newPos);
    }

    private static VoxelShape cageShape(BlockState state) {
        int mask = 0;
        for (Direction direction : Direction.values()) {
            if (isConnected(state, direction)) {
                mask |= 1 << direction.ordinal();
            }
        }
        return CAGE_SHAPES[mask];
    }

    private static VoxelShape[] buildCageShapes() {
        VoxelShape[] shapes = new VoxelShape[CONNECTION_MASK_COUNT];
        for (int mask = 0; mask < shapes.length; mask++) {
            shapes[mask] = buildCageShape(mask);
        }
        return shapes;
    }

    private static VoxelShape buildCageShape(int connectionMask) {
        VoxelShape result = Shapes.empty();
        for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(connectionMask, ySide) && !connected(connectionMask, zSide)) {
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(0, y0, z0, 16, y0 + BAR, z0 + BAR));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!connected(connectionMask, xSide) && !connected(connectionMask, zSide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(x0, 0, z0, x0 + BAR, 16, z0 + BAR));
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
                if (!connected(connectionMask, xSide) && !connected(connectionMask, ySide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 16 - BAR;
                    double y0 = ySide == Direction.DOWN ? 0 : 16 - BAR;
                    result = Shapes.or(result, Block.box(x0, y0, 0, x0 + BAR, y0 + BAR, 16));
                }
            }
        }
        return result;
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CONNECTION_PROPERTIES.get(direction));
    }

    private static boolean bridgeSuppressed(BlockGetter level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        var assembly = manager.getAssemblyAt(pos).orElse(null);
        return assembly != null && manager.pendingContraptionMove(assembly.id()).isPresent();
    }

    private boolean connectsTo(BlockState state, BlockState other) {
        return other.is(this)
                && other.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static boolean connected(int connectionMask, Direction direction) {
        return (connectionMask & (1 << direction.ordinal())) != 0;
    }
}
