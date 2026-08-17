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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Create-specific regression tests without hard Create class references, preserving optional loading. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateContraptionKineticGameTests {
    private CreateContraptionKineticGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 140)
    public static void contraptionDisconnectPreservesInternalMultiFrameKinetics(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos leftFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos rightFrame = leftFrame.east();
        placeFrame(level, leftFrame);
        placeFrame(level, rightFrame);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(leftFrame)
                .orElseThrow(() -> new AssertionError("missing multi-Frame assembly"));
        check(assembly.frames().equals(Set.of(leftFrame, rightFrame)), "Frames did not form one assembly");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        Block creativeMotor = requireCreateBlock("creative_motor");
        Block shaft = requireCreateBlock("shaft");
        BlockPos motorLocal = MiniCoordinateMapper.frameToMini(assembly, leftFrame, 1, 0, 0);
        BlockPos shaftLocal = MiniCoordinateMapper.frameToMini(assembly, rightFrame, 0, 0, 0);
        check(shaftLocal.equals(motorLocal.east()), "test mini cells are not contiguous across the Frame boundary");

        BlockPos motorGlobal = MechanismSubLevelService.toPlotPosition(child, motorLocal);
        BlockPos shaftGlobal = MechanismSubLevelService.toPlotPosition(child, shaftLocal);
        BlockState motorState = creativeMotor.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST);
        BlockState shaftState = shaft.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X);

        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(motorGlobal, motorState, Block.UPDATE_ALL)),
                "could not place mini creative motor");
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(shaftGlobal, shaftState, Block.UPDATE_ALL)),
                "could not place mini shaft across Frame boundary");

        helper.runAfterDelay(10, () -> {
            float motorSpeedBefore = kineticSpeed(level.getBlockEntity(motorGlobal));
            float shaftSpeedBefore = kineticSpeed(level.getBlockEntity(shaftGlobal));
            check(Math.abs(motorSpeedBefore) > 0.001F, "mini creative motor never became a kinetic source");
            check(Math.abs(shaftSpeedBefore) > 0.001F, "cross-Frame mini shaft never joined the motor network");

            UUID id = assembly.id();
            Set<BlockPos> frames = Set.copyOf(assembly.frames());
            check(manager.prepareContraptionMoves(level, Map.of(id, frames), BlockPos.ZERO, false),
                    "could not journal Create capture");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(id));

            float motorSpeedAfter = kineticSpeed(level.getBlockEntity(motorGlobal));
            float shaftSpeedAfter = kineticSpeed(level.getBlockEntity(shaftGlobal));
            check(Math.abs(motorSpeedAfter - motorSpeedBefore) < 0.001F,
                    "Create capture cleared the internal mini generator speed");
            check(Math.abs(shaftSpeedAfter - shaftSpeedBefore) < 0.001F,
                    "Create capture cleared the cross-Frame mini shaft speed");
            check(manager.pendingContraptionMove(id).isPresent(),
                    "capture journal disappeared while checking moving-boundary kinetics");
            helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void splittingFrameGraphRebuildsSurvivingMiniKinetics(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos leftFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos bridgeFrame = leftFrame.east();
        BlockPos poweredFrame = bridgeFrame.east();
        placeFrame(level, leftFrame);
        placeFrame(level, bridgeFrame);
        placeFrame(level, poweredFrame);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(poweredFrame)
                .orElseThrow(() -> new AssertionError("missing three-Frame assembly"));
        check(assembly.frames().size() == 3, "test Frames did not form one assembly");
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        Block creativeMotor = requireCreateBlock("creative_motor");
        Block shaft = requireCreateBlock("shaft");
        BlockPos motorLocal = MiniCoordinateMapper.frameToMini(assembly, poweredFrame, 0, 0, 0);
        BlockPos shaftLocal = MiniCoordinateMapper.frameToMini(assembly, poweredFrame, 1, 0, 0);
        BlockPos motorGlobal = MechanismSubLevelService.toPlotPosition(child, motorLocal);
        BlockPos shaftGlobal = MechanismSubLevelService.toPlotPosition(child, shaftLocal);
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        motorGlobal,
                        creativeMotor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                        Block.UPDATE_ALL)),
                "could not place split-test mini motor");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        shaftGlobal,
                        shaft.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                        Block.UPDATE_ALL)),
                "could not place split-test mini shaft");

        helper.runAfterDelay(12, () -> {
            check(Math.abs(kineticSpeed(level.getBlockEntity(motorGlobal))) > 0.001F,
                    "split-test motor never started");
            check(Math.abs(kineticSpeed(level.getBlockEntity(shaftGlobal))) > 0.001F,
                    "split-test shaft never joined its source");
            check(level.destroyBlock(bridgeFrame, false), "could not break Frame articulation point");

            helper.runAfterDelay(28, () -> {
                MechanismAssembly survivor = manager.getAssemblyAt(poweredFrame)
                        .orElseThrow(() -> new AssertionError("powered Frame lost assembly after split"));
                check(!survivor.id().equals(assembly.id()),
                        "powered disconnected component was not materialized as a split assembly");
                ServerSubLevel survivorChild = MechanismSubLevelService.findExisting(level, survivor);
                check(survivorChild != null && !survivorChild.isRemoved(),
                        "split component lost its managed mini world");

                BlockPos newMotorGlobal = MechanismSubLevelService.toPlotPosition(
                        survivorChild,
                        MiniCoordinateMapper.frameToMini(survivor, poweredFrame, 0, 0, 0));
                BlockPos newShaftGlobal = MechanismSubLevelService.toPlotPosition(
                        survivorChild,
                        MiniCoordinateMapper.frameToMini(survivor, poweredFrame, 1, 0, 0));
                check(Math.abs(kineticSpeed(level.getBlockEntity(newMotorGlobal))) > 0.001F,
                        "mini motor did not recover after Frame split");
                check(Math.abs(kineticSpeed(level.getBlockEntity(newShaftGlobal))) > 0.001F,
                        "mini shaft remained stopped after Frame split until source restart");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void diagonalSeparateFramesUseNativeSmallToLargeCogRatio(GameTestHelper helper) {
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
                "diagonal Frames unexpectedly merged into one assembly");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, targetAssembly);
        check(sourceChild != null && targetChild != null, "could not materialize diagonal mini worlds");

        Block creativeMotor = requireCreateBlock("creative_motor");
        Block smallCog = requireCreateBlock("cogwheel");
        Block largeCog = requireCreateBlock("large_cogwheel");
        BlockPos motorLocal = MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 0, 1, 1);
        BlockPos smallLocal = MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 1, 1);
        BlockPos largeLocal = MiniCoordinateMapper.frameToMini(targetAssembly, targetFrame, 1, 0, 0);
        BlockPos motorGlobal = MechanismSubLevelService.toPlotPosition(sourceChild, motorLocal);
        BlockPos smallGlobal = MechanismSubLevelService.toPlotPosition(sourceChild, smallLocal);
        BlockPos largeGlobal = MechanismSubLevelService.toPlotPosition(targetChild, largeLocal);

        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        motorGlobal,
                        creativeMotor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
                        Block.UPDATE_ALL)),
                "could not place diagonal-test motor");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        smallGlobal,
                        smallCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                        Block.UPDATE_ALL)),
                "could not place diagonal-test small cog");
        check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                        largeGlobal,
                        largeCog.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                        Block.UPDATE_ALL)),
                "could not place diagonal-test large cog");

        helper.runAfterDelay(20, () -> {
            float motorSpeed = kineticSpeed(level.getBlockEntity(motorGlobal));
            float smallSpeed = kineticSpeed(level.getBlockEntity(smallGlobal));
            float largeSpeed = kineticSpeed(level.getBlockEntity(largeGlobal));
            check(Math.abs(motorSpeed) > 0.001F, "diagonal-test motor never started");
            check(Math.abs(smallSpeed) > 0.001F, "small cog never joined its local motor");
            check(Math.abs(largeSpeed) > 0.001F,
                    "large cog in separate diagonal Frame did not receive rotation");
            check(Math.signum(smallSpeed) == -Math.signum(largeSpeed),
                    "diagonal small/large cogs did not counter-rotate like ordinary Create gears");
            check(Math.abs(Math.abs(smallSpeed / largeSpeed) - 2.0F) < 0.01F,
                    "diagonal cross-Frame ratio was not Create's native small-to-large 2:1 ratio");
            helper.succeed();
        });
    }

    private static Block requireCreateBlock(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("create", path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        check(block != null && id.equals(BuiltInRegistries.BLOCK.getKey(block)), "missing Create block " + id);
        return block;
    }

    private static float kineticSpeed(BlockEntity blockEntity) {
        check(blockEntity != null, "missing Create kinetic BlockEntity");
        try {
            Object value = blockEntity.getClass().getMethod("getTheoreticalSpeed").invoke(blockEntity);
            check(value instanceof Number, "Create kinetic speed accessor returned a non-number");
            return ((Number) value).floatValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("could not inspect Create kinetic BlockEntity without hard-linking Create", exception);
        }
    }

    private static void placeFrame(ServerLevel level, BlockPos pos) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
