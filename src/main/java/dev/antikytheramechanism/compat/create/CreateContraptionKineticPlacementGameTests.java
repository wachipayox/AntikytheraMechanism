package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.CreatePlacementCommitService;
import dev.antikytheramechanism.assembly.FrameOrientation;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for kinetic topology that becomes valid only after Create places a Frame. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateContraptionKineticPlacementGameTests {
    private CreateContraptionKineticPlacementGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void staticSourceConnectsToMovedAssemblyAfterPlacement(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos movingSourceFrame = helper.absolutePos(new BlockPos(8, 2, 2));
        BlockPos movingDestination = sourceFrame.offset(0, 1, 1);
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, movingSourceFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing static source assembly"));
        MechanismAssembly movingAssembly = manager.getAssemblyAt(movingSourceFrame)
                .orElseThrow(() -> new AssertionError("missing moving target assembly"));
        check(!sourceAssembly.id().equals(movingAssembly.id()),
                "far Frames unexpectedly shared one assembly before placement test");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        ServerSubLevel movingChild = MechanismSubLevelService.ensureForContent(level, movingAssembly);
        check(sourceChild != null && movingChild != null,
                "could not materialize static-to-moved kinetic children");

        Block motor = requireCreateBlock("creative_motor");
        Block smallCog = requireCreateBlock("cogwheel");
        Block largeCog = requireCreateBlock("large_cogwheel");
        BlockPos motorGlobal = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 0, 1, 1);
        BlockPos smallGlobal = miniGlobal(sourceChild, sourceAssembly, sourceFrame, 1, 1, 1);
        BlockPos largeGlobal = miniGlobal(movingChild, movingAssembly, movingSourceFrame, 1, 0, 0);

        place(level, motorGlobal,
                motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                "static source motor");
        place(level, smallGlobal,
                smallCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "static boundary small cog");
        place(level, largeGlobal,
                largeCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "moving large cog");

        helper.runAfterDelay(20, () -> {
            assertPowered(level, "static source did not stabilize", motorGlobal, smallGlobal);
            assertStopped(level, "far moving cog unexpectedly had a source before placement", largeGlobal);

            UUID movingId = movingAssembly.id();
            moveSingleFrameThroughCreateCommit(
                    level, movingAssembly, movingSourceFrame, movingDestination);
            check(manager.getAssemblyAt(movingDestination)
                            .map(MechanismAssembly::id)
                            .filter(movingId::equals)
                            .isPresent(),
                    "moving target assembly did not own its placed Frame");

            helper.runAfterDelay(10, () -> {
                assertPowered(level,
                        "existing static network did not discover the newly adjacent moved mechanism",
                        smallGlobal, largeGlobal);
                assertNativeSmallLargeRatio(level, smallGlobal, largeGlobal,
                        "static -> moved diagonal gear ratio was not native Create behavior");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void movedSourceConnectsToStaticAssemblyAfterPlacement(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos movingSourceFrame = helper.absolutePos(new BlockPos(8, 2, 2));
        BlockPos movingDestination = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos staticTargetFrame = movingDestination.offset(0, 1, 1);
        placeFrame(level, movingSourceFrame, Direction.NORTH);
        placeFrame(level, staticTargetFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly movingAssembly = manager.getAssemblyAt(movingSourceFrame)
                .orElseThrow(() -> new AssertionError("missing moving source assembly"));
        MechanismAssembly staticTargetAssembly = manager.getAssemblyAt(staticTargetFrame)
                .orElseThrow(() -> new AssertionError("missing static target assembly"));
        check(!movingAssembly.id().equals(staticTargetAssembly.id()),
                "far source and target unexpectedly shared one assembly");

        ServerSubLevel movingChild = MechanismSubLevelService.ensureForContent(level, movingAssembly);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, staticTargetAssembly);
        check(movingChild != null && targetChild != null,
                "could not materialize moved-to-static kinetic children");

        Block motor = requireCreateBlock("creative_motor");
        Block smallCog = requireCreateBlock("cogwheel");
        Block largeCog = requireCreateBlock("large_cogwheel");
        BlockPos motorGlobal = miniGlobal(movingChild, movingAssembly, movingSourceFrame, 0, 1, 1);
        BlockPos smallGlobal = miniGlobal(movingChild, movingAssembly, movingSourceFrame, 1, 1, 1);
        BlockPos largeGlobal = miniGlobal(targetChild, staticTargetAssembly, staticTargetFrame, 1, 0, 0);

        place(level, motorGlobal,
                motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                "moving source motor");
        place(level, smallGlobal,
                smallCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "moving boundary small cog");
        place(level, largeGlobal,
                largeCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                "static target large cog");

        helper.runAfterDelay(20, () -> {
            assertPowered(level, "moving source did not stabilize before movement", motorGlobal, smallGlobal);
            assertStopped(level, "static target unexpectedly had a source before moving source arrived", largeGlobal);

            moveSingleFrameThroughCreateCommit(
                    level, movingAssembly, movingSourceFrame, movingDestination);

            helper.runAfterDelay(10, () -> {
                assertPowered(level,
                        "newly placed moving source did not advertise into the existing static network",
                        smallGlobal, largeGlobal);
                assertNativeSmallLargeRatio(level, smallGlobal, largeGlobal,
                        "moved -> static diagonal gear ratio was not native Create behavior");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void movedAssemblyCanBecomeNewBridgeBetweenStaticNetworks(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos bridgeDestination = sourceFrame.offset(1, 0, 1);
        BlockPos targetFrame = bridgeDestination.offset(1, 0, 1);
        BlockPos bridgeSourceFrame = helper.absolutePos(new BlockPos(8, 2, 6));
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, bridgeSourceFrame, Direction.NORTH);
        placeFrame(level, targetFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing bridge-test static source assembly"));
        MechanismAssembly bridgeAssembly = manager.getAssemblyAt(bridgeSourceFrame)
                .orElseThrow(() -> new AssertionError("missing bridge-test moving assembly"));
        MechanismAssembly targetAssembly = manager.getAssemblyAt(targetFrame)
                .orElseThrow(() -> new AssertionError("missing bridge-test static target assembly"));
        check(!sourceAssembly.id().equals(bridgeAssembly.id())
                        && !sourceAssembly.id().equals(targetAssembly.id())
                        && !bridgeAssembly.id().equals(targetAssembly.id()),
                "bridge placement setup did not start as three separate assemblies");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        ServerSubLevel bridgeChild = MechanismSubLevelService.ensureForContent(level, bridgeAssembly);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, targetAssembly);
        check(sourceChild != null && bridgeChild != null && targetChild != null,
                "could not materialize bridge placement mini worlds");

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
        BlockPos bridgeIncoming = miniGlobal(bridgeChild, bridgeAssembly, bridgeSourceFrame, 0, 1, 0);
        BlockPos bridgeOutgoing = miniGlobal(bridgeChild, bridgeAssembly, bridgeSourceFrame, 1, 1, 1);
        BlockPos targetIncoming = miniGlobal(targetChild, targetAssembly, targetFrame, 0, 1, 0);
        BlockPos targetMain = miniGlobal(targetChild, targetAssembly, targetFrame, 1, 1, 1);
        BlockPos targetBranchX = miniGlobal(targetChild, targetAssembly, targetFrame, 0, 1, 1);
        BlockPos targetBranchZ = miniGlobal(targetChild, targetAssembly, targetFrame, 1, 1, 0);

        place(level, motorGlobal,
                motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
                "bridge-placement source motor");
        place(level, sourceFeed, smallY, "bridge-placement source feed cog");
        place(level, sourceBoundary, smallY, "bridge-placement source boundary cog");
        place(level, bridgeIncoming, largeY, "moving bridge incoming cog");
        place(level, bridgeOutgoing, smallY, "moving bridge outgoing cog");
        place(level, targetIncoming, largeY, "bridge-placement target incoming cog");
        place(level, targetMain, smallY, "bridge-placement target main cog");
        place(level, targetBranchX, smallY, "bridge-placement target X branch cog");
        place(level, targetBranchZ, smallY, "bridge-placement target Z branch cog");

        helper.runAfterDelay(30, () -> {
            assertPowered(level, "bridge-placement source network never stabilized",
                    sourceFeed, sourceBoundary);
            assertStopped(level, "far moving bridge unexpectedly received rotation before placement",
                    bridgeIncoming, bridgeOutgoing);
            assertStopped(level, "static target unexpectedly received rotation before bridge existed",
                    targetIncoming, targetMain, targetBranchX, targetBranchZ);

            moveSingleFrameThroughCreateCommit(
                    level, bridgeAssembly, bridgeSourceFrame, bridgeDestination);

            helper.runAfterDelay(12, () -> {
                assertPowered(level,
                        "placed assembly did not become a live bridge from source to target",
                        sourceFeed, sourceBoundary,
                        bridgeIncoming, bridgeOutgoing,
                        targetIncoming, targetMain, targetBranchX, targetBranchZ);
                check(manager.getAssemblyAt(bridgeDestination)
                                .map(MechanismAssembly::id)
                                .filter(bridgeAssembly.id()::equals)
                                .isPresent(),
                        "bridge assembly lost ownership after successful placement");
                helper.succeed();
            });
        });
    }

    private static void moveSingleFrameThroughCreateCommit(
            ServerLevel level,
            MechanismAssembly movingAssembly,
            BlockPos sourceFrame,
            BlockPos destinationFrame) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        UUID movingId = movingAssembly.id();
        Set<BlockPos> sourceFrames = Set.copyOf(movingAssembly.frames());
        check(sourceFrames.equals(Set.of(sourceFrame)),
                "single-Frame movement helper received a multi-Frame assembly");
        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(movingId, sourceFrames),
                        BlockPos.ZERO,
                        false),
                "could not journal Create kinetic placement capture");

        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(movingId));
        CreateMiniKineticLifecycle.disconnectContraptionCapture(level, Set.of(movingId));
        check(level.removeBlock(sourceFrame, false),
                "could not remove moving Frame during simulated Create capture");

        FrameOrientation orientation = movingAssembly.orientation();
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(movingId, Set.of(destinationFrame)),
                        Map.of(movingId, destinationFrame),
                        Map.of(movingId, poseAt(destinationFrame, orientation))),
                "could not journal Create kinetic placement destination");
        placeFrame(level, destinationFrame, orientation.front());

        CreatePlacementCommitService.CommitResult result =
                CreatePlacementCommitService.finalizePreparedPlacement(level, List.of(movingId));
        check(result.committed(), "Create kinetic placement did not commit");
        CreateContraptionPlacementCommit.finishCommittedPlacement(level, Set.of(movingId), result);
        check(manager.pendingContraptionMove(movingId).isEmpty(),
                "Create kinetic placement journal survived committed placement");
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond quaternion = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(
                origin.getX() + .5,
                origin.getY() + .5,
                origin.getZ() + .5,
                quaternion.x,
                quaternion.y,
                quaternion.z,
                quaternion.w);
    }

    private static void assertNativeSmallLargeRatio(
            ServerLevel level,
            BlockPos smallPosition,
            BlockPos largePosition,
            String message) {
        float small = kineticSpeed(level, smallPosition);
        float large = kineticSpeed(level, largePosition);
        check(Math.abs(small) > 0.001F && Math.abs(large) > 0.001F,
                message + " (one side is stopped)");
        check(Math.signum(small) == -Math.signum(large),
                message + " (gears do not counter-rotate)");
        check(Math.abs(Math.abs(small / large) - 2.0F) < 0.01F,
                message + " (small=" + small + ", large=" + large + ")");
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
                child,
                MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z));
    }

    private static void place(ServerLevel level, BlockPos position, BlockState state, String label) {
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(position, state, Block.UPDATE_ALL)),
                "could not place " + label);
    }

    private static void assertPowered(ServerLevel level, String message, BlockPos... positions) {
        for (BlockPos position : positions) {
            check(Math.abs(kineticSpeed(level, position)) > 0.001F,
                    message + " at " + position + " (speed=" + kineticSpeed(level, position) + ")");
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
