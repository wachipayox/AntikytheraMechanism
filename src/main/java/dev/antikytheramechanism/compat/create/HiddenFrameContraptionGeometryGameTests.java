package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.HiddenFrameGeometryPolicy;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runtime regression for HIDDEN hosted mini connectivity changing after Create extraction. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenFrameContraptionGeometryGameTests {
    private static final double EPSILON = 1.0E-6;

    private HiddenFrameContraptionGeometryGameTests() {
    }

    /**
     * The windmill keeps exactly 32 mini sails (8.0 effective sail power) throughout the mutation.
     * Removing a non-sail bridge therefore cannot trip the minimum-sail rule; it must stop only because
     * the foreign-hosted HIDDEN payload has split into disconnected mini islands while in flight.
     */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void breakingHiddenMiniBridgeDisassemblesWithoutSailDeficit(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Block windmill = requireBlock("create", "windmill_bearing");
        BlockPos rootFrame = helper.absolutePos(new BlockPos(4, 5, 4));
        BlockPos rootBearing = rootFrame.west();
        BlockPos rootSupport = rootFrame.below();

        check(level.setBlock(
                        rootBearing,
                        facing(windmill.defaultBlockState(), Direction.EAST),
                        Block.UPDATE_ALL),
                "could not place root windmill bearing");
        check(level.setBlock(rootSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place root foreign-host support");

        List<BlockPos> rootFrames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            BlockPos frame = rootFrame.east(index);
            check(level.setBlock(frame, ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                    "could not place source Frame " + index);
            rootFrames.add(frame);
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(rootFrame)
                .orElseThrow(() -> new AssertionError("missing source five-Frame assembly"));
        check(source.frames().size() == 5, "source Frames did not merge before Sable hosting");
        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, source);
        check(sourceChild != null && !sourceChild.isRemoved(), "could not stage managed mini child");

        List<BlockPos> hostedPayload = new ArrayList<>();
        hostedPayload.add(rootBearing);
        hostedPayload.add(rootSupport);
        hostedPayload.addAll(rootFrames);
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                hostedPayload,
                new BoundingBox3i(
                        rootBearing.getX(), rootSupport.getY(), rootFrame.getZ(),
                        rootFrames.getLast().getX(), rootFrame.getY(), rootFrame.getZ()));
        check(host != null && !host.isRemoved(), "could not create foreign host for windmill fixture");

        BlockPos hostedRoot = host.getPlot().getCenterBlock();
        BlockPos bearingPos = hostedRoot.west();
        BlockPos supportPos = hostedRoot.below();
        check(Sable.HELPER.getContaining(level, hostedRoot) == host,
                "hosted Frame coordinate did not resolve to the foreign Sable host");
        check(Sable.HELPER.getContaining(level, bearingPos) == host,
                "hosted bearing coordinate did not resolve to the foreign Sable host");

        MechanismAssembly assembly = manager.getAssemblyAt(hostedRoot)
                .orElseThrow(() -> new AssertionError("Frame assembly did not follow Sable host move"));
        UUID assemblyId = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        check(child != null && !child.isRemoved(), "managed mini child disappeared during Sable host move");

        List<BlockPos> frames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            BlockPos frame = hostedRoot.east(index);
            check(manager.getAssemblyAt(frame).map(MechanismAssembly::id).filter(assemblyId::equals).isPresent(),
                    "hosted Frame mapping missing at index " + index);
            frames.add(frame);
        }
        check(level.getBlockState(supportPos).is(Blocks.STONE), "foreign-host support did not relocate");

        BlockState sail = requireBlock("create", "white_sail").defaultBlockState();
        fillFrame(level, child, assembly, frames.get(0), sail);
        fillFrame(level, child, assembly, frames.get(1), sail);
        fillFrame(level, child, assembly, frames.get(3), sail);
        fillFrame(level, child, assembly, frames.get(4), sail);
        BlockPos bridgeWest = miniCell(child, assembly, frames.get(2), 0, 0, 0);
        BlockPos bridgeEast = miniCell(child, assembly, frames.get(2), 1, 0, 0);
        check(level.setBlock(bridgeWest, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place west non-sail bridge cell");
        check(level.setBlock(bridgeEast, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place east non-sail bridge cell");

        check(manager.setFrameShellMode(level, hostedRoot, FrameShellMode.HIDDEN),
                "could not hide hosted Frame assembly");
        HiddenFrameGeometryPolicy.request(level, assemblyId);
        HiddenFrameGeometryPolicy.tick(level);
        check(manager.getAssembly(assemblyId).orElseThrow().shellMode() == FrameShellMode.HIDDEN,
                "connected hosted fixture was rejected before Create assembly");

        BlockEntity bearing = requireBlockEntity(level, bearingPos);
        invokeNoArgs(bearing, "assemble");
        check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                "foreign-hosted windmill did not assemble from 8.0 hidden mini sail power");
        Object moved = invokeNoArgs(bearing, "getMovedContraption");
        check(moved != null, "assembled hosted windmill has no moved contraption");
        Object rawContraption = invokeNoArgs(moved, "getContraption");
        check(rawContraption instanceof com.simibubi.create.content.contraptions.bearing.BearingContraption,
                "hosted windmill did not produce a BearingContraption");
        com.simibubi.create.content.contraptions.bearing.BearingContraption contraption =
                (com.simibubi.create.content.contraptions.bearing.BearingContraption) rawContraption;

        helper.runAfterDelay(5, () -> {
            check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                    "valid connected hidden payload was falsely disassembled");
            CreateHiddenFrameConnectivityGuard.Result before =
                    CreateHiddenFrameConnectivityGuard.evaluate(level, contraption);
            check(before.verdict() == CreateHiddenFrameConnectivityGuard.Verdict.VALID,
                    "connected in-flight hidden payload failed connectivity guard");
            checkClose(DynamicMiniSailSnapshot.capture(level, contraption).miniSailPower(), 8.0,
                    "fixture did not retain exactly 32 mini sails before bridge break");

            check(level.removeBlock(bridgeWest, false), "could not break in-flight non-sail mini bridge");
            checkClose(DynamicMiniSailSnapshot.capture(level, contraption).miniSailPower(), 8.0,
                    "breaking a non-sail bridge incorrectly changed mini sail power");
            CreateHiddenFrameConnectivityGuard.Result broken =
                    CreateHiddenFrameConnectivityGuard.evaluate(level, contraption);
            check(broken.verdict() == CreateHiddenFrameConnectivityGuard.Verdict.INVALID,
                    "disconnected in-flight hidden payload was not detected");
            check(broken.invalidHiddenAssemblies().contains(assemblyId),
                    "connectivity guard did not identify the affected hidden assembly");

            helper.runAfterDelay(8, () -> {
                check(!booleanValue(invokeNoArgs(bearing, "isRunning")),
                        "bearing remained assembled after hidden mini payload became disconnected");

                // The mini write already queued the normal policy while the Create journal was alive.
                // Once ordinary disassembly has placed the Frames, it can safely persist HIDDEN -> NORMAL.
                HiddenFrameGeometryPolicy.tick(level);
                MechanismAssembly restored = manager.getAssembly(assemblyId)
                        .orElseThrow(() -> new AssertionError("assembly vanished after bearing disassembly"));
                check(restored.shellMode() == FrameShellMode.NORMAL,
                        "disconnected assembly stayed HIDDEN after Create returned its Frames");
                helper.succeed();
            });
        });
    }

    private static void fillFrame(
            ServerLevel level,
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame,
            BlockState state) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos cell = miniCell(child, assembly, frame, x, y, z);
                    check(level.setBlock(cell, state, Block.UPDATE_ALL),
                            "could not fill mini cell " + x + "," + y + "," + z + " in " + frame);
                }
            }
        }
    }

    private static BlockPos miniCell(
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame,
            int x,
            int y,
            int z) {
        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
        return MechanismSubLevelService.toPlotPosition(child, mini);
    }

    private static Block requireBlock(String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR && !id.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            throw new AssertionError("missing required block " + id);
        }
        return block;
    }

    private static BlockState facing(BlockState state, Direction direction) {
        if (!state.hasProperty(BlockStateProperties.FACING)) {
            throw new AssertionError("bearing state has no FACING property: " + state);
        }
        return state.setValue(BlockStateProperties.FACING, direction);
    }

    private static BlockEntity requireBlockEntity(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new AssertionError("missing block entity at " + pos);
        }
        return blockEntity;
    }

    private static Object invokeNoArgs(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("missing method " + target.getClass().getName() + "." + name, exception);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("cannot access method " + target.getClass().getName() + "." + name, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("method " + name + " threw", cause);
        }
    }

    private static boolean booleanValue(Object value) {
        return (Boolean) value;
    }

    private static void checkClose(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
