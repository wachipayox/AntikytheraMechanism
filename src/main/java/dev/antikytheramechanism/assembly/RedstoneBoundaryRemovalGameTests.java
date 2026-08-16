package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
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

        // Make the whole WEST mini face sturdy, matching a wall attachment a player can actually
        // place on the Frame. One inner lamp sits directly behind the lower-north conductor cell.
        int occupiedMask = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                BlockPos physical = new BlockPos(0, y, z);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly, framePosition, physical.getX(), physical.getY(), physical.getZ());
                check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                                local, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                        "could not seed WEST-face mini conductor " + physical);
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(physical.getX(), physical.getY(), physical.getZ());
            }
        }
        BlockPos innerPhysical = new BlockPos(1, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.physicalFrameCellToMini(
                assembly, framePosition, innerPhysical.getX(), innerPhysical.getY(), innerPhysical.getZ());
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        innerLocal, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed indirect mini lamp");
        occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(1, 0, 0);

        child.getPlot().updateBoundingBox();
        check(!MechanismSubLevelService.isPhysicallyEmpty(child), "redstone fixture child remained physically empty");
        BlockState frameState = level.getBlockState(framePosition);
        if (frameState.getValue(MechanismFrameBlock.EMPTY)) {
            check(level.setBlock(framePosition,
                            frameState.setValue(MechanismFrameBlock.EMPTY, false),
                            Block.UPDATE_ALL),
                    "could not mark populated Frame non-empty");
        }
        check(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity,
                "Frame block entity disappeared while building fixture");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePosition);
        frame.setOccupiedMask(occupiedMask);

        // Place the wall lever with vanilla BlockItem logic so FACE/FACING and survival are not
        // guessed by the GameTest. Only POWERED is then toggled directly to avoid testing input UI.
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack leverStack = new ItemStack(Blocks.LEVER);
        player.setItemInHand(InteractionHand.MAIN_HAND, leverStack);
        Vec3 hitLocation = Vec3.atCenterOf(framePosition).add(-.5, 0.0, 0.0);
        BlockHitResult hit = new BlockHitResult(hitLocation, Direction.WEST, framePosition, false);
        InteractionResult placed = leverStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(placed.consumesAction(), "vanilla wall-lever placement failed on WEST Frame face");
        BlockState lever = level.getBlockState(sourcePosition);
        helper.assertTrue(lever.is(Blocks.LEVER), "wall lever did not appear beside Frame");
        helper.assertTrue(level.setBlock(
                        sourcePosition,
                        lever.setValue(BlockStateProperties.POWERED, true),
                        Block.UPDATE_ALL),
                "could not power wall lever");
        MiniWorldEnvironment.parentBlockChanged(level, sourcePosition);

        helper.runAfterDelay(2, () -> {
            BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);
            helper.assertTrue(level.getBlockState(sourcePosition).is(Blocks.LEVER)
                            && level.getBlockState(sourcePosition).getValue(BlockStateProperties.POWERED),
                    "powered wall lever disappeared before removal");
            helper.assertTrue(
                    MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid wall-lever circuit did not indirectly power the mini lamp");

            BlockState poweredReceiver = child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal);
            helper.assertTrue(poweredReceiver.is(Blocks.REDSTONE_LAMP),
                    "indirect mini lamp disappeared before source removal");
            if (!poweredReceiver.getValue(BlockStateProperties.LIT)) {
                helper.assertTrue(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                                innerLocal,
                                poweredReceiver.setValue(BlockStateProperties.LIT, true),
                                Block.UPDATE_ALL),
                        "could not establish already-proven powered receiver state");
            }

            level.removeBlock(sourcePosition, false);
            helper.assertTrue(level.getBlockState(sourcePosition).isAir(),
                    "macro wall lever still exists after removal");
            MiniWorldEnvironment.parentBlockChanged(level, sourcePosition);

            helper.runAfterDelay(8, () -> {
                helper.assertFalse(
                        MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                        "indirect mini lamp still sees power after macro source removal");
                BlockState after = child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal);
                helper.assertTrue(after.is(Blocks.REDSTONE_LAMP), "indirect mini lamp disappeared");
                helper.assertFalse(after.getValue(BlockStateProperties.LIT),
                        "indirect mini lamp stayed latched after macro source removal");
                helper.succeed();
            });
        });
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
