package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Real Create bearing capture/disassembly coverage without hard optional-Create class references. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateFramePresentationContraptionGameTests {
    private CreateFramePresentationContraptionGameTests() {}

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 260)
    public static void bearingRoundTripPreservesHiddenPresentationUuidChildAndMiniPayload(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos bearingPos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos framePos = bearingPos.east();
        Block bearing = requireCreateBlock("mechanical_bearing");
        BlockState bearingState = bearing.defaultBlockState();
        check(bearingState.hasProperty(BlockStateProperties.FACING),
                "Create mechanical bearing lacks FACING property");
        bearingState = bearingState.setValue(BlockStateProperties.FACING, Direction.EAST);
        check(level.setBlock(bearingPos, bearingState, Block.UPDATE_ALL),
                "could not place Create mechanical bearing");
        placeFrame(level, framePos);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly before = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = before.id();
        check(manager.setFrameShellMode(level, framePos, FrameShellMode.HIDDEN),
                "could not set HIDDEN before Create capture");
        check(manager.setFrameSkin(level, framePos, FrameSkin.BRASS_CASING),
                "could not set BRASS skin before Create capture");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, before);
        check(child != null && !child.isRemoved(), "could not create managed child before Create capture");
        UUID childId = child.getUniqueId();
        BlockPos mini = MiniCoordinateMapper.frameToMini(before, framePos, 1, 0, 1);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        mini, Blocks.EMERALD_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini payload before Create capture");
        child.getPlot().updateBoundingBox();

        BlockEntity bearingBlockEntity = level.getBlockEntity(bearingPos);
        check(bearingBlockEntity != null, "mechanical bearing BlockEntity was not created");
        invokeNoArgs(bearingBlockEntity, "assemble");

        Object movedContraption = invokeNoArgsResult(bearingBlockEntity, "getMovedContraption");
        check(movedContraption != null, "Create bearing did not create a moving contraption");
        check(level.getBlockState(framePos).isAir(),
                "Create bearing capture left the physical Frame at its ROOT position");
        check(manager.pendingContraptionMove(assemblyId).isPresent(),
                "Create bearing capture did not retain the relocation journal while moving");
        MechanismAssembly during = manager.getAssembly(assemblyId).orElseThrow();
        check(during.shellMode() == FrameShellMode.HIDDEN && during.skin() == FrameSkin.BRASS_CASING,
                "Create capture changed presentation authority while moving");
        ServerSubLevel movingChild = MechanismSubLevelService.findExisting(level, during);
        check(movingChild != null && childId.equals(movingChild.getUniqueId()),
                "Create capture recreated/replaced managed child while moving");
        check(movingChild.getPlot().getEmbeddedLevelAccessor().getBlockState(mini).is(Blocks.EMERALD_BLOCK),
                "Create capture changed mini payload while moving");

        invokeNoArgs(bearingBlockEntity, "disassemble");

        BlockState restoredState = level.getBlockState(framePos);
        check(restoredState.is(ModRegistries.MECHANISM_FRAME.get()),
                "Create disassembly did not restore physical Frame");
        check(restoredState.getValue(MechanismFrameBlock.SHELL_MODE) == FrameShellMode.HIDDEN,
                "Create disassembly did not restore HIDDEN BlockState");
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                "Create disassembly did not restore Frame BlockEntity");
        MechanismFrameBlockEntity restoredFrame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
        check(assemblyId.equals(restoredFrame.getAssemblyId()),
                "Create disassembly restored Frame with different assembly UUID");
        check(restoredFrame.getPresentationSkin() == FrameSkin.BRASS_CASING,
                "Create disassembly did not restore BRASS skin cache");

        MechanismAssembly after = manager.getAssemblyAt(framePos).orElseThrow();
        check(assemblyId.equals(after.id()), "Create round-trip changed assembly UUID");
        check(after.shellMode() == FrameShellMode.HIDDEN && after.skin() == FrameSkin.BRASS_CASING,
                "Create round-trip changed presentation authority");
        check(manager.pendingContraptionMove(assemblyId).isEmpty(),
                "Create disassembly left relocation journal active after commit");
        ServerSubLevel afterChild = MechanismSubLevelService.findExisting(level, after);
        check(afterChild != null && childId.equals(afterChild.getUniqueId()),
                "Create round-trip recreated/replaced managed child");
        BlockPos restoredMini = MiniCoordinateMapper.frameToMini(after, framePos, 1, 0, 1);
        check(afterChild.getPlot().getEmbeddedLevelAccessor().getBlockState(restoredMini).is(Blocks.EMERALD_BLOCK),
                "Create round-trip lost or remapped mini payload");
        helper.succeed();
    }

    private static Block requireCreateBlock(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("create", path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        check(block != null && id.equals(BuiltInRegistries.BLOCK.getKey(block)),
                "missing Create block " + id);
        return block;
    }

    private static void invokeNoArgs(Object target, String methodName) {
        invokeNoArgsResult(target, methodName);
    }

    private static Object invokeNoArgsResult(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("Create method " + methodName + " failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not invoke Create method " + methodName, exception);
        }
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
