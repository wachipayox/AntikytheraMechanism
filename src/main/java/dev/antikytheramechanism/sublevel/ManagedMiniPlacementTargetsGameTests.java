package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for placement-helper routing between separate neighboring Frames. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManagedMiniPlacementTargetsGameTests {
    private ManagedMiniPlacementTargetsGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void diagonalHelperTargetRoutesIntoNeighborFramePlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos destinationFrame = sourceFrame.offset(0, 1, 1);
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, destinationFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing source assembly"));
        MechanismAssembly destinationAssembly = manager.getAssemblyAt(destinationFrame)
                .orElseThrow(() -> new AssertionError("missing diagonal destination assembly"));
        check(!sourceAssembly.id().equals(destinationAssembly.id()),
                "diagonal Frames unexpectedly merged before helper routing test");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        check(sourceChild != null, "could not materialize helper source child");
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(
                sourceChild,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 1, 1));
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        sourceGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place helper source support");

        // One mini step in +Y and +Z crosses from the source corner into the physically diagonal
        // Frame. In the source plot this coordinate is deliberately outside its FrameMask.
        BlockPos proposedGlobal = sourceChild.getPlot().getCenterBlock().offset(1, 2, 2);
        check(!ManagedMiniPlacementTargets.isOwnedTarget(level, sourceGlobal, proposedGlobal),
                "diagonal proposal was incorrectly owned by the source FrameMask");

        ManagedMiniPlacementTargets.NeighborFrameTarget routed =
                ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                        level, sourceGlobal, proposedGlobal)
                        .orElseThrow(() -> new AssertionError(
                                "valid diagonal helper target was not routed to its neighbor Frame"));

        ServerSubLevel destinationChild = MechanismSubLevelService.findExisting(level, destinationAssembly);
        check(destinationChild != null, "routing did not materialize destination child");
        BlockPos expectedGlobal = MechanismSubLevelService.toPlotPosition(
                destinationChild,
                MiniCoordinateMapper.frameToMini(
                        destinationAssembly, destinationFrame, 1, 0, 0));

        check(routed.destinationAssemblyId().equals(destinationAssembly.id()),
                "diagonal helper routed to the wrong assembly");
        check(routed.destinationFrame().equals(destinationFrame),
                "diagonal helper routed to the wrong physical Frame");
        check(routed.destinationGlobalPosition().equals(expectedGlobal),
                "diagonal helper did not resolve the matching destination mini cell");
        check(routed.stateRotation() == Rotation.NONE,
                "equal-yaw diagonal Frames unexpectedly rotated helper state");
        check(level.getBlockState(proposedGlobal).isAir(),
                "routing test unexpectedly populated the unowned source-plot coordinate");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void rotatedNeighborHelperTargetRebasesCellAndStateYaw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos destinationFrame = sourceFrame.east();
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, destinationFrame, Direction.EAST);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing rotated-test source assembly"));
        MechanismAssembly destinationAssembly = manager.getAssemblyAt(destinationFrame)
                .orElseThrow(() -> new AssertionError("missing rotated-test destination assembly"));
        check(!sourceAssembly.id().equals(destinationAssembly.id()),
                "different-yaw adjacent Frames unexpectedly merged");
        check(destinationAssembly.orientation().front() == Direction.EAST,
                "destination assembly did not retain its distinct physical yaw");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        check(sourceChild != null, "could not materialize rotated-test source child");
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(
                sourceChild,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 1, 1));
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        sourceGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place rotated-test helper source support");

        BlockPos proposedGlobal = sourceChild.getPlot().getCenterBlock().offset(2, 1, 1);
        ManagedMiniPlacementTargets.NeighborFrameTarget routed =
                ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                        level, sourceGlobal, proposedGlobal)
                        .orElseThrow(() -> new AssertionError(
                                "valid helper target in differently yawed neighbor was rejected"));

        ServerSubLevel destinationChild = MechanismSubLevelService.findExisting(level, destinationAssembly);
        check(destinationChild != null, "rotated routing did not materialize destination child");
        BlockPos physicalDestinationCell = new BlockPos(0, 1, 1);
        BlockPos logicalDestinationCell = destinationAssembly.orientation().physicalCellToLogical(
                physicalDestinationCell.getX(),
                physicalDestinationCell.getY(),
                physicalDestinationCell.getZ());
        BlockPos expectedGlobal = MechanismSubLevelService.toPlotPosition(
                destinationChild,
                MiniCoordinateMapper.frameToMini(
                        destinationAssembly,
                        destinationFrame,
                        logicalDestinationCell.getX(),
                        logicalDestinationCell.getY(),
                        logicalDestinationCell.getZ()));

        check(routed.destinationGlobalPosition().equals(expectedGlobal),
                "rotated helper cell was not rebased into destination logical axes");
        check(routed.stateRotation() == Rotation.COUNTERCLOCKWISE_90,
                "NORTH -> EAST Frame handoff did not preserve physical state yaw");
        check(level.getBlockState(proposedGlobal).isAir(),
                "rotated routing populated an unowned source-plot coordinate");

        // The same source-relative mini coordinate must remain forbidden when no physical Frame owns
        // the corresponding neighboring cube; the QoL exception must never become a macro escape.
        BlockPos macroEscape = sourceChild.getPlot().getCenterBlock().offset(-1, 1, 1);
        check(ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                        level, sourceGlobal, macroEscape).isEmpty(),
                "helper routing accepted a neighboring macro position with no Mechanism Frame");
        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos position, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Frame at " + position + " facing " + facing);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
