package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
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

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void removedMacroSignalSourceUpdatesIndirectMiniReceiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePosition = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos sourcePosition = framePosition.west();

        // Put the source in place before the Frame so the setup itself does not depend on the
        // topology-deferred macro -> mini addition path under test.
        check(level.setBlock(sourcePosition, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not place macro redstone source");
        placeFrame(level, framePosition);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition)
                .orElseThrow(() -> new AssertionError("missing Frame assembly"));
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // The boundary stone is directly strongly powered through the projected macro source. The
        // lamp one mini cell behind it is powered indirectly through that conductor. Removing the
        // macro source must therefore update not just the boundary cell, but the second-order mini
        // receiver behind it as vanilla's source update centre would.
        BlockPos boundaryLocal = MiniCoordinateMapper.frameToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.frameToMini(assembly, framePosition, 1, 0, 0);
        BlockPos boundaryGlobal = MechanismSubLevelService.toPlotPosition(child, boundaryLocal);
        BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);

        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(boundaryGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place directly powered mini conductor");
        BlockState litLamp = Blocks.REDSTONE_LAMP.defaultBlockState()
                .setValue(BlockStateProperties.LIT, true);
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(innerGlobal, litLamp, Block.UPDATE_ALL)),
                "could not place indirectly powered mini lamp");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                "regression setup did not indirectly power the mini lamp");

        check(level.removeBlock(sourcePosition, false), "could not remove macro redstone source");

        // Source removal changes the boundary overlap mask, so Antikythera deliberately defers the
        // exact face by one tick. Run that queued reconciliation explicitly; the lamp itself still
        // uses vanilla's four-tick switch-off delay after receiving the missing neighbour update.
        RedstoneBoundaryRefreshScheduler.runScheduled(level, framePosition);
        check(!MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                "indirect mini lamp still sees power after macro source removal");

        helper.runAfterDelay(6, () -> {
            BlockState after = level.getBlockState(innerGlobal);
            check(after.is(Blocks.REDSTONE_LAMP), "indirect mini lamp disappeared");
            check(!after.getValue(BlockStateProperties.LIT),
                    "indirect mini lamp stayed latched after macro source removal");
            helper.succeed();
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
