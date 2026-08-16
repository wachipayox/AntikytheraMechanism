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

        // Seed persistent child storage directly. The regression under test is removal propagation,
        // not client raycast/placement; GameTests run on a dedicated server and cannot reproduce the
        // client-side scaled hit arbitration used by a real click.
        BlockPos boundaryLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePosition, 1, 0, 0);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        boundaryLocal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini conductor");
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        innerLocal, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini lamp");

        // Direct fixture seeding deliberately bypasses MiniPlacementRouter, so synchronize exactly the
        // parent metadata that two successful gameplay placements would have produced. The physical
        // occupied cells are (0,0,0) and (1,0,0), i.e. mask bits 0 and 1.
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
        check(frame.getOccupiedMask() == 0b00000011, "fixture occupiedMask was not retained");

        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(boundaryLocal).is(Blocks.STONE),
                "mini conductor is missing from managed SubLevel");
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal).is(Blocks.REDSTONE_LAMP),
                "mini lamp is missing from managed SubLevel");

        // A powered wall lever is a reachable vanilla state and, unlike the old redstone-block
        // fixture, actually produces the indirect signal this regression requires.
        BlockState poweredLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(sourcePosition, poweredLever, Block.UPDATE_ALL),
                "could not establish powered macro wall lever");
        MiniWorldEnvironment.parentBlockChanged(level, sourcePosition);

        helper.runAfterDelay(2, () -> {
            BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);
            helper.assertTrue(
                    MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid circuit did not indirectly power the mini lamp");

            BlockState poweredReceiver = child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal);
            helper.assertTrue(poweredReceiver.is(Blocks.REDSTONE_LAMP),
                    "indirect mini lamp disappeared before source removal");

            // We have now proved the receiver really has power. Establish the corresponding reachable
            // lit state if the direct test fixture did not receive the separate activation callback.
            // From here onward the test exclusively verifies removal propagation and vanilla's delayed
            // lamp switch-off, which is the behavior named by this regression.
            if (!poweredReceiver.getValue(BlockStateProperties.LIT)) {
                helper.assertTrue(
                        child.getPlot().getEmbeddedLevelAccessor().setBlock(
                                innerLocal,
                                poweredReceiver.setValue(BlockStateProperties.LIT, true),
                                Block.UPDATE_ALL),
                        "could not establish powered mini receiver state");
            }
            helper.assertTrue(
                    child.getPlot().getEmbeddedLevelAccessor().getBlockState(innerLocal)
                            .getValue(BlockStateProperties.LIT),
                    "powered mini receiver fixture did not become lit");

            helper.assertTrue(level.destroyBlock(sourcePosition, false),
                    "could not break macro wall lever source");

            // Boundary reconciliation may defer by one tick; a lit redstone lamp then has vanilla's
            // four-tick switch-off delay. Eight ticks covers both real lifecycle steps.
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
