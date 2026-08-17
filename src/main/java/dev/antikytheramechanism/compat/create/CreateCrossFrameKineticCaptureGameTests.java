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
        BlockPos motorGlobal = MechanismSubLevelService.toPlotPosition(
                sourceChild,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 0, 1, 1));
        BlockPos smallGlobal = MechanismSubLevelService.toPlotPosition(
                sourceChild,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 1, 1));
        BlockPos largeGlobal = MechanismSubLevelService.toPlotPosition(
                targetChild,
                MiniCoordinateMapper.frameToMini(targetAssembly, targetFrame, 1, 0, 0));

        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        motorGlobal,
                        motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                        Block.UPDATE_ALL)),
                "could not place capture-test motor");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        smallGlobal,
                        smallCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                        Block.UPDATE_ALL)),
                "could not place capture-test small cog");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        largeGlobal,
                        largeCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                        Block.UPDATE_ALL)),
                "could not place capture-test large cog");

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

    private static Block requireCreateBlock(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("create", path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        check(block != null && id.equals(BuiltInRegistries.BLOCK.getKey(block)),
                "missing Create block " + id);
        return block;
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
