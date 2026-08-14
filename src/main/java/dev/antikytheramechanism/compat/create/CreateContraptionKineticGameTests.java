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
