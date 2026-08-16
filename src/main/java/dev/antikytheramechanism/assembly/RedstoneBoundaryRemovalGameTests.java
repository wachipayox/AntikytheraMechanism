package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.interaction.MiniPlacementRouter;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for topology-deferred macro redstone source removal. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstoneBoundaryRemovalGameTests {
    private RedstoneBoundaryRemovalGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void removedMacroSignalSourceUpdatesIndirectMiniReceiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePosition = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos sourcePosition = framePosition.west();

        placeFrame(level, framePosition);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition)
                .orElseThrow(() -> new AssertionError("missing Frame assembly"));
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Build the valid vanilla circuit through the exact authoritative mini placement path. The
        // GameTest supplies the selected physical cell because client raycast arbitration does not run
        // on a dedicated GameTest server; everything after that selection is identical to gameplay.
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        placeMiniBlockLikePlayer(level, player, framePosition, new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        placeMiniBlockLikePlayer(level, player, framePosition, new BlockPos(1, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos boundaryLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 1, 0, 0);
        BlockPos boundaryGlobal = MechanismSubLevelService.toPlotPosition(child, boundaryLocal);
        BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);
        check(!level.getBlockState(framePosition).getValue(MechanismFrameBlock.EMPTY),
                "authoritative mini placement left Frame marked EMPTY");
        check(level.getBlockState(boundaryGlobal).is(Blocks.STONE), "mini conductor is missing");
        check(level.getBlockState(innerGlobal).is(Blocks.REDSTONE_LAMP), "mini lamp is missing");

        // A powered wall lever is a reachable vanilla state and, unlike the old redstone-block
        // fixture, really can strongly power the boundary conductor. Establish that state directly so
        // this regression tests macro->mini removal rather than wall-attachment click geometry.
        BlockState poweredLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(sourcePosition, poweredLever, Block.UPDATE_ALL),
                "could not establish powered macro wall lever");
        MiniWorldEnvironment.parentBlockChanged(level, sourcePosition);

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(
                    MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid circuit did not indirectly power the mini lamp");
            BlockState beforeRemoval = level.getBlockState(innerGlobal);
            helper.assertTrue(beforeRemoval.is(Blocks.REDSTONE_LAMP),
                    "indirect mini lamp disappeared before source removal");
            helper.assertTrue(beforeRemoval.getValue(BlockStateProperties.LIT),
                    "indirect mini lamp did not light from the valid macro source");

            helper.assertTrue(level.destroyBlock(sourcePosition, false),
                    "could not break macro wall lever source");

            // Boundary reconciliation may defer by one tick; a lit redstone lamp then has vanilla's
            // four-tick switch-off delay. Eight ticks covers both real lifecycle steps.
            helper.runAfterDelay(8, () -> {
                helper.assertFalse(
                        MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                        "indirect mini lamp still sees power after macro source removal");
                BlockState after = level.getBlockState(innerGlobal);
                helper.assertTrue(after.is(Blocks.REDSTONE_LAMP), "indirect mini lamp disappeared");
                helper.assertFalse(after.getValue(BlockStateProperties.LIT),
                        "indirect mini lamp stayed latched after macro source removal");
                helper.succeed();
            });
        });
    }

    private static void placeMiniBlockLikePlayer(
            ServerLevel level,
            Player player,
            BlockPos framePosition,
            BlockPos physicalCell,
            BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = MiniPlacementRouter.placeSelectedCellForGameTest(
                level,
                framePosition,
                Direction.UP,
                physicalCell.getX(),
                physicalCell.getY(),
                physicalCell.getZ(),
                player,
                InteractionHand.MAIN_HAND,
                stack);
        check(result.consumesAction(), "authoritative mini placement failed at " + physicalCell);
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Frame");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
