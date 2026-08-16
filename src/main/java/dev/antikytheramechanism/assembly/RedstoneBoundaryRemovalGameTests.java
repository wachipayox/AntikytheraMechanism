package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
    public static void removedMacroSignalSourceUpdatesMiniReceiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        placeFrame(level, framePos);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        helper.assertTrue(child != null && !child.isRemoved(), "could not materialize managed mini world");

        BlockPos lampLocal = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePos, 0, 0, 0);
        BlockState litLamp = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        helper.assertTrue(child.getPlot().getEmbeddedLevelAccessor().setBlock(lampLocal, litLamp, Block.UPDATE_ALL),
                "could not seed mini lamp");
        child.getPlot().setBoundingBox(new BoundingBox3i(0, 0, 0, 1, 1, 1));
        helper.assertFalse(MechanismSubLevelService.isPhysicallyEmpty(child),
                "seeded managed mini world remained physically empty");

        BlockState frameState = level.getBlockState(framePos);
        if (frameState.getValue(MechanismFrameBlock.EMPTY)) {
            level.setBlock(framePos, frameState.setValue(MechanismFrameBlock.EMPTY, false),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        helper.assertTrue(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                "Frame block entity missing");
        ((MechanismFrameBlockEntity) level.getBlockEntity(framePos)).setOccupiedMask(
                1 << MiniCoordinateMapper.cellIndex(0, 0, 0));

        BlockPos sourcePos = framePos.west();
        level.setBlock(sourcePos, poweredWallLever(Direction.WEST),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        helper.assertTrue(level.getBlockState(sourcePos).is(Blocks.LEVER),
                "powered macro lever fixture did not survive");
        MiniWorldEnvironment.parentBlockChanged(level, sourcePos);

        helper.runAfterDelay(2, () -> {
            BlockPos lampGlobal = MechanismSubLevelService.toPlotPosition(child, lampLocal);
            helper.assertTrue(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lampGlobal)),
                    "valid macro lever did not power the boundary mini lamp");
            helper.assertTrue(child.getPlot().getEmbeddedLevelAccessor().getBlockState(lampLocal).is(Blocks.REDSTONE_LAMP),
                    "mini lamp disappeared before source removal");

            level.removeBlock(sourcePos, false);
            helper.assertTrue(level.getBlockState(sourcePos).isAir(), "macro source still exists after removal");
            MiniWorldEnvironment.parentBlockChanged(level, sourcePos);

            helper.runAfterDelay(8, () -> {
                helper.assertFalse(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lampGlobal)),
                        "mini lamp still sees macro power after source removal");
                BlockState after = child.getPlot().getEmbeddedLevelAccessor().getBlockState(lampLocal);
                helper.assertTrue(after.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared after source removal");
                helper.assertFalse(after.getValue(BlockStateProperties.LIT),
                        "mini lamp stayed latched after macro source removal");
                helper.succeed();
            });
        });
    }

    private static BlockState poweredWallLever(Direction face) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face)
                .setValue(BlockStateProperties.POWERED, true);
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        if (!level.setBlock(position, state, Block.UPDATE_ALL) && !level.getBlockState(position).equals(state)) {
            throw new AssertionError("could not place Frame");
        }
    }
}
