package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;

/** Regression coverage for stale virtual Create networks when one Frame enters a contraption. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateCrossFrameKineticCaptureGameTests {
    private CreateCrossFrameKineticCaptureGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void contraptionCaptureDetachesExistingDiagonalVirtualSource(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos targetFrame = sourceFrame.offset(0, 1, 1);
        placeFrame(level, sourceFrame);
        placeFrame(level, targetFrame);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing source assembly"));
        MechanismAssembly targetAssembly = manager.getAssemblyAt(targetFrame)
                .orElseThrow(() -> new AssertionError("missing target assembly"));
        check(!sourceAssembly.id().equals(targetAssembly.id()),
                "diagonal Frames unexpectedly merged before capture test");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, targetAssembly);
        check(sourceChild != null && targetChild != null,
                "could not materialize capture-test mini worlds");

        Block motor = requireCreateBlock("creative_motor");
        Block smallCog = requireCreateBlock("cogwheel");
        Block largeCog = requireCreateBlock("large_cogwheel");
        BlockPos motorGlobal = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 0, 1, 1);
        BlockPos smallGlobal = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 1, 1, 1);
        BlockPos largeGlobal = miniGlobal(targetChild, targetAssembly, targetFrame, 1, 0, 0);

        place(level, motorGlobal,
                motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                "capture-test motor");
        place(level, smallGlobal,
                smallCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "capture-test small cog");
        place(level, largeGlobal,
                largeCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "capture-test large cog");

        helper.runAfterDelay(20, () -> {
            float smallBefore = kineticSpeed(level, smallGlobal);
            float largeBefore = kineticSpeed(level, largeGlobal);
            check(Math.abs(smallBefore) > 0.001F, "source small cog never started");
            check(Math.abs(largeBefore) > 0.001F,
                    "target large cog never received the initial virtual diagonal source");

            check(manager.prepareContraptionMoves(
                            level,
                            Map.of(sourceAssembly.id(), Set.copyOf(sourceAssembly.frames())),
                            BlockPos.ZERO,
                            false),
                    "could not journal source Frame capture");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(sourceAssembly.id()));
            CreateMiniKineticLifecycle.disconnectContraptionCapture(
                    level, Set.of(sourceAssembly.id()));

            // The moving assembly keeps its real internal motor -> small-cog network. The target is
            // a different static assembly, so the old virtual edge must already be gone before Create
            // removes the outer Frame. Waiting for a later neighbour update is what caused the ghost.
            float smallAfter = kineticSpeed(level, smallGlobal);
            float largeAfter = kineticSpeed(level, largeGlobal);
            check(Math.abs(smallAfter) > 0.001F,
                    "capture teardown incorrectly destroyed the moving assembly's internal kinetics");
            check(Math.abs(largeAfter) < 0.001F,
                    "static diagonal cog retained phantom rotation after source Frame entered capture");
            check(manager.pendingContraptionMove(sourceAssembly.id()).isPresent(),
                    "capture journal disappeared before virtual kinetic teardown was verified");
            helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void bridgeCaptureRepairsWholeMultiCogNetworkWithoutAlternatingStaleNodes(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        // Horizontal face-diagonals reproduce the three-Frame bridge setup used in-world: the middle
        // Frame is captured while both end Frames remain static.
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos bridgeFrame = sourceFrame.offset(1, 0, 1);
        BlockPos targetFrame = bridgeFrame.offset(1, 0, 1);
        placeFrame(level, sourceFrame);
        placeFrame(level, bridgeFrame);
        placeFrame(level, targetFrame);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing static source assembly"));
        MechanismAssembly bridgeAssembly = manager.getAssemblyAt(bridgeFrame)
                .orElseThrow(() -> new AssertionError("missing moving bridge assembly"));
        MechanismAssembly targetAssembly = manager.getAssemblyAt(targetFrame)
                .orElseThrow(() -> new AssertionError("missing static target assembly"));
        check(!sourceAssembly.id().equals(bridgeAssembly.id())
                        && !sourceAssembly.id().equals(targetAssembly.id())
                        && !bridgeAssembly.id().equals(targetAssembly.id()),
                "horizontal diagonal Frames unexpectedly merged before bridge capture test");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        ServerSubLevel bridgeChild = MechanismSubLevelService.ensureForContent(level, bridgeAssembly);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, targetAssembly);
        check(sourceChild != null && bridgeChild != null && targetChild != null,
                "could not materialize three-Frame kinetic mini worlds");

        Block motor = requireCreateBlock("creative_motor");
        Block smallCog = requireCreateBlock("cogwheel");
        Block largeCog = requireCreateBlock("large_cogwheel");
        BlockState smallY = smallCog.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        BlockState largeY = largeCog.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y);

        BlockPos motorGlobal = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 0, 0, 1);
        BlockPos sourceFeed = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 0, 1, 1);
        BlockPos sourceBoundary = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 1, 1, 1);

        BlockPos bridgeIncoming = miniGlobal(bridgeChild, bridgeAssembly, bridgeFrame, 0, 1, 0);
        BlockPos bridgeOutgoing = miniGlobal(bridgeChild, bridgeAssembly, bridgeFrame, 1, 1, 1);

        BlockPos targetIncoming = miniGlobal(targetChild, targetAssembly, targetFrame, 0, 1, 0);
        BlockPos targetMain = miniGlobal(targetChild, targetAssembly, targetFrame, 1, 1, 1);
        BlockPos targetBranchX = miniGlobal(targetChild, targetAssembly, targetFrame, 0, 1, 1);
        BlockPos targetBranchZ = miniGlobal(targetChild, targetAssembly, targetFrame, 1, 1, 0);

        place(level, motorGlobal,
                motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
                "three-Frame source motor");
        place(level, sourceFeed, smallY, "source feed cog");
        place(level, sourceBoundary, smallY, "source boundary cog");
        place(level, bridgeIncoming, largeY, "bridge incoming large cog");
        place(level, bridgeOutgoing, smallY, "bridge outgoing cog");
        place(level, targetIncoming, largeY, "target incoming large cog");
        place(level, targetMain, smallY, "target main cog");
        place(level, targetBranchX, smallY, "target X branch cog");
        place(level, targetBranchZ, smallY, "target Z branch cog");

        helper.runAfterDelay(30, () -> {
            assertPowered(level, "source network never stabilized before bridge capture",
                    sourceFeed, sourceBoundary);
            assertPowered(level, "bridge network never received diagonal rotation",
                    bridgeIncoming, bridgeOutgoing);
            assertPowered(level, "target multi-cog network never received rotation through bridge",
                    targetIncoming, targetMain, targetBranchX, targetBranchZ);

            check(manager.prepareContraptionMoves(
                            level,
                            Map.of(bridgeAssembly.id(), Set.copyOf(bridgeAssembly.frames())),
                            BlockPos.ZERO,
                            false),
                    "could not journal middle bridge Frame capture");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(bridgeAssembly.id()));
            CreateMiniKineticLifecycle.disconnectContraptionCapture(
                    level, Set.of(bridgeAssembly.id()));

            assertPowered(level, "bridge capture damaged the static source-side component",
                    sourceFeed, sourceBoundary);
            assertStopped(level, "captured bridge retained rotation from a static source",
                    bridgeIncoming, bridgeOutgoing);
            assertStopped(level, "static target retained phantom or alternating rotation after bridge cut",
                    targetIncoming, targetMain, targetBranchX, targetBranchZ);
            check(manager.pendingContraptionMove(bridgeAssembly.id()).isPresent(),
                    "bridge capture journal disappeared before kinetic cut verification");

            // The old broad rebuild ran on the following tick and could leave a one-on/one-off
            // pattern. Verify after several ticks, not just synchronously inside the capture hook.
            helper.runAfterDelay(8, () -> {
                assertPowered(level, "source-side cogs became partially stale after delayed maintenance",
                        sourceFeed, sourceBoundary);
                assertStopped(level, "bridge cogs were re-sourced after delayed maintenance",
                        bridgeIncoming, bridgeOutgoing);
                assertStopped(level, "target cogs developed delayed phantom/alternating rotation",
                        targetIncoming, targetMain, targetBranchX, targetBranchZ);

                check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                                motorGlobal, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)),
                        "could not remove source motor after bridge capture");

                // A healthy surviving Create component must still react to an ordinary source
                // removal. Replacing each cog to make it update is specifically forbidden here.
                helper.runAfterDelay(8, () -> {
                    assertStopped(level, "surviving source component ignored later motor removal",
                            sourceFeed, sourceBoundary);
                    assertStopped(level, "target component regained phantom rotation after motor removal",
                            targetIncoming, targetMain, targetBranchX, targetBranchZ);
                    helper.succeed();
                });
            });
        });
    }

    private static Block requireCreateBlock(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("create", path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        check(block != null && id.equals(BuiltInRegistries.BLOCK.getKey(block)),
                "missing Create block " + id);
        return block;
    }

    private static BlockPos miniGlobal(
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame,
            int x,
            int y,
            int z) {
        return MechanismSubLevelService.toPlotPosition(
                child, MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z));
    }

    private static void place(ServerLevel level, BlockPos position, BlockState state, String label) {
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(position, state, Block.UPDATE_ALL)),
                "could not place " + label);
    }

    private static void assertPowered(ServerLevel level, String message, BlockPos... positions) {
        for (BlockPos position : positions) {
            check(Math.abs(kineticSpeed(level, position)) > 0.001F,
                    message + " at " + position);
        }
    }

    private static void assertStopped(ServerLevel level, String message, BlockPos... positions) {
        for (BlockPos position : positions) {
            check(Math.abs(kineticSpeed(level, position)) < 0.001F,
                    message + " at " + position + " (speed=" + kineticSpeed(level, position) + ")");
        }
    }

    private static float kineticSpeed(ServerLevel level, BlockPos position) {
        Object blockEntity = level.getBlockEntity(position);
        check(blockEntity != null, "missing kinetic BlockEntity at " + position);
        try {
            Object value = blockEntity.getClass().getMethod("getTheoreticalSpeed").invoke(blockEntity);
            check(value instanceof Number, "Create kinetic speed accessor returned a non-number");
            return ((Number) value).floatValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("could not inspect Create kinetic BlockEntity", exception);
        }
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
