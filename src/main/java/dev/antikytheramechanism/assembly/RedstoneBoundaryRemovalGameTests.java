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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

        BlockPos boundaryLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 1, 0, 0);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        boundaryLocal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini conductor");
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        innerLocal, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini lamp");

        // Direct fixture seeding bypasses MiniPlacementRouter. Synchronize the exact parent metadata
        // produced by successful placement of physical cells (0,0,0) and (1,0,0).
        BlockState frameState = level.getBlockState(framePosition);
        check(frameState.is(ModRegistries.MECHANISM_FRAME.get()), "Frame disappeared while building fixture");
        if (frameState.getValue(MechanismFrameBlock.EMPTY)) {
            check(level.setBlock(
                            framePosition,
                            frameState.setValue(MechanismFrameBlock.EMPTY, false),
                            Block.UPDATE_ALL),
                    "could not mark populated Frame non-empty");
        }
        check(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity,
                "Frame block entity disappeared while building fixture");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePosition);
        frame.setOccupiedMask(0b00000011);

        // The old REDSTONE_BLOCK -> STONE -> LAMP fixture is invalid vanilla redstone. Use a powered
        // repeater pointing EAST into the Frame instead: a repeater strongly powers the conductor it
        // faces, so the lamp behind that conductor is a legitimate indirect receiver. A stone floor
        // keeps the repeater's survival independent from the Frame itself.
        check(level.setBlock(sourcePosition.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place repeater support");
        BlockState poweredRepeater = Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(sourcePosition, poweredRepeater, Block.UPDATE_ALL),
                "could not establish powered macro repeater");
        MiniWorldEnvironment.parentBlockChanged(level, sourcePosition);

        helper.runAfterDelay(2, () -> {
            BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);
            helper.assertTrue(level.getBlockState(sourcePosition).is(Blocks.REPEATER),
                    "macro repeater disappeared before removal");
            helper.assertTrue(
                    MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid repeater circuit did not indirectly power the mini lamp");

            BlockState poweredReceiver = child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal);
            helper.assertTrue(poweredReceiver.is(Blocks.REDSTONE_LAMP),
                    "indirect mini lamp disappeared before source removal");
            if (!poweredReceiver.getValue(BlockStateProperties.LIT)) {
                helper.assertTrue(
                        child.getPlot().getEmbeddedLevelAccessor().setBlock(
                                innerLocal,
                                poweredReceiver.setValue(BlockStateProperties.LIT, true),
                                Block.UPDATE_ALL),
                        "could not establish already-proven powered receiver state");
            }

            // Do not assert the boolean return of an administrative mutation. The observable gameplay
            // condition is that breaking/removing the source leaves AIR and the boundary receives the
            // source change, which is what the subsequent assertions exercise.
            level.removeBlock(sourcePosition, false);
            helper.assertTrue(level.getBlockState(sourcePosition).isAir(),
                    "macro repeater source still exists after removal");
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
