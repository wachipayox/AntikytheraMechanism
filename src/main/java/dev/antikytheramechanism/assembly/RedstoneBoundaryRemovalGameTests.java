package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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

        // Build the exact valid circuit through the same ItemStack -> MiniPlacementRouter path used
        // by gameplay. The previous fixture wrote directly into the Sable plot, which bypassed the
        // Frame occupancy state and block placement/update semantics entirely.
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        placeMiniBlockLikePlayer(player, framePosition, new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        placeMiniBlockLikePlayer(player, framePosition, new BlockPos(1, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos boundaryLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 1, 0, 0);
        BlockPos boundaryGlobal = MechanismSubLevelService.toPlotPosition(child, boundaryLocal);
        BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);
        check(!level.getBlockState(framePosition).getValue(dev.antikytheramechanism.frame.MechanismFrameBlock.EMPTY),
                "player-style mini placement left Frame marked EMPTY");
        check(level.getBlockState(boundaryGlobal).is(Blocks.STONE), "player-style mini conductor is missing");
        check(level.getBlockState(innerGlobal).is(Blocks.REDSTONE_LAMP), "player-style mini lamp is missing");

        placeMacroLeverLikePlayer(player, framePosition, Direction.WEST);
        check(level.getBlockState(sourcePosition).is(Blocks.LEVER), "macro wall lever did not survive placement");
        helper.useBlock(helper.relativePos(sourcePosition), player);
        check(level.getBlockState(sourcePosition).getValue(BlockStateProperties.POWERED),
                "macro wall lever did not turn on through normal interaction");

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(
                    MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid player-built circuit did not indirectly power the mini lamp");
            BlockState beforeRemoval = level.getBlockState(innerGlobal);
            helper.assertTrue(beforeRemoval.is(Blocks.REDSTONE_LAMP),
                    "indirect mini lamp disappeared before source removal");
            helper.assertTrue(beforeRemoval.getValue(BlockStateProperties.LIT),
                    "player-built indirect mini lamp did not light from the macro lever");

            helper.assertTrue(level.destroyBlock(sourcePosition, false),
                    "could not break macro wall lever source");

            // The boundary topology reconciliation may defer by one tick; a lit redstone lamp then
            // has vanilla's four-tick switch-off delay. Eight ticks covers both real lifecycle steps.
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
            Player player,
            BlockPos framePosition,
            BlockPos physicalCell,
            BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Vec3 hitLocation = new Vec3(
                framePosition.getX() + (physicalCell.getX() + 0.5) * 0.5,
                framePosition.getY() + (physicalCell.getY() + 0.5) * 0.5,
                framePosition.getZ() + (physicalCell.getZ() + 0.5) * 0.5);
        BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, framePosition, false);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(result.consumesAction(), "player-style mini placement failed at " + physicalCell);
    }

    private static void placeMacroLeverLikePlayer(Player player, BlockPos framePosition, Direction physicalFace) {
        ItemStack stack = new ItemStack(Blocks.LEVER);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Vec3 hitLocation = Vec3.atCenterOf(framePosition).add(
                physicalFace.getStepX() * 0.5,
                physicalFace.getStepY() * 0.5,
                physicalFace.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitLocation, physicalFace, framePosition, false);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(result.consumesAction(), "player-style macro lever placement failed on " + physicalFace);
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
