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

        // Mirror the valid vanilla circuit represented by a powered wall lever attached to a solid
        // conductor with a lamp one cell behind it. A redstone block beside a solid block does NOT
        // power that block indirectly in vanilla, so it is not a valid fixture for this regression.
        BlockPos boundaryLocal = MiniCoordinateMapper.frameToMini(assembly, framePosition, 0, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.frameToMini(assembly, framePosition, 1, 0, 0);
        BlockPos boundaryGlobal = MechanismSubLevelService.toPlotPosition(child, boundaryLocal);
        BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);

        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(boundaryGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place directly powered mini conductor");
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(innerGlobal, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place indirectly powered mini lamp");

        // Direct plot writes bypass MiniPlacementRouter, whose successful gameplay path refreshes the
        // parent Frame. Reproduce that synchronized state before adding the macro source.
        manager.refreshFrame(level, framePosition);

        BlockState poweredLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(sourcePosition, poweredLever, Block.UPDATE_ALL),
                "could not place powered macro wall lever");
        check(level.getBlockState(sourcePosition).is(Blocks.LEVER),
                "powered macro wall lever did not survive placement");

        // Let the normal boundary scheduler and vanilla redstone updates establish the circuit rather
        // than forcing LIT=true on the lamp. This is the same observable state reached in gameplay.
        helper.runAfterDelay(2, () -> {
            check(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                    "valid wall-lever fixture did not indirectly power the mini lamp");
            BlockState beforeRemoval = level.getBlockState(innerGlobal);
            check(beforeRemoval.is(Blocks.REDSTONE_LAMP), "indirect mini lamp disappeared before source removal");
            check(beforeRemoval.getValue(BlockStateProperties.LIT),
                    "indirect mini lamp was not lit by the valid macro source");

            check(level.removeBlock(sourcePosition, false), "could not remove macro wall lever source");

            // Source removal may defer the boundary topology replay by one tick; the lamp then uses
            // vanilla's four-tick switch-off delay. Eight ticks leaves deterministic margin for both.
            helper.runAfterDelay(8, () -> {
                check(!MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal)),
                        "indirect mini lamp still sees power after macro source removal");
                BlockState after = level.getBlockState(innerGlobal);
                check(after.is(Blocks.REDSTONE_LAMP), "indirect mini lamp disappeared");
                check(!after.getValue(BlockStateProperties.LIT),
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
