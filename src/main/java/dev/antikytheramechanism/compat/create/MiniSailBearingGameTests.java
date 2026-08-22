package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
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
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runtime parity coverage for Create/Aeronautics bearings consuming dynamic mini-sail overlays. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiniSailBearingGameTests {
    private static final double EPSILON = 1.0E-4;

    private MiniSailBearingGameTests() {
    }

    /**
     * Five Frames are captured, but only four begin full: 32 mini sails * .25 = Create's default
     * minimum of 8. The fifth Frame gives us a runtime-positive mutation without rebuilding the
     * contraption. Removing one base mini after returning to the threshold must then disassemble via
     * Create's normal bearing route, and it must remain stopped rather than queued-reassembling.
     */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void windmillUsesDynamicMiniAreaAndDisassemblesBelowMinimum(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos bearingPos = helper.absolutePos(new BlockPos(2, 4, 4));
        List<BlockPos> frames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            BlockPos frame = bearingPos.east(index + 1);
            frames.add(frame);
            placeFrame(level, frame);
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(frames.getFirst())
                .orElseThrow(() -> new AssertionError("missing five-Frame windmill assembly"));
        check(assembly.frames().size() == 5, "windmill Frames did not merge into one assembly");
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create windmill mini child");

        BlockState sail = requireBlock("create", "white_sail").defaultBlockState();
        List<BlockPos> baseMiniCells = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 4; frameIndex++) {
            baseMiniCells.addAll(fillFrame(level, child, assembly, frames.get(frameIndex), sail));
        }
        List<BlockPos> extraMiniCells = frameMiniCells(child, assembly, frames.get(4));
        check(baseMiniCells.size() == 32, "windmill fixture did not create 32 mini sails");

        Block windmill = requireBlock("create", "windmill_bearing");
        check(level.setBlock(bearingPos, facing(windmill.defaultBlockState(), Direction.EAST), Block.UPDATE_ALL),
                "could not place windmill bearing");
        BlockEntity bearing = requireBlockEntity(level, bearingPos);
        invokeNoArgs(bearing, "assemble");
        check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                "windmill did not assemble from exactly 8.0 mini sail power");

        Object moved = invokeNoArgs(bearing, "getMovedContraption");
        check(moved != null, "assembled mini windmill has no moved contraption");
        Object contraption = invokeNoArgs(moved, "getContraption");
        check(intValue(invokeNoArgs(contraption, "getSailBlocks")) == 0,
                "mini sails were written into native BearingContraption.sailBlocks");
        checkClose(snapshot(contraption).miniSailPower(), 8.0,
                "32 mini sails did not contribute exactly 8.0 area power");
        checkClose(4.0 * DynamicMiniSailSnapshot.MINI_SAIL_POWER, 1.0,
                "four-mini surface invariant is not 1 macro sail");

        // Positive runtime mutation: add four cells = +1.0 effective sail power.
        for (int index = 0; index < 4; index++) {
            check(level.setBlock(extraMiniCells.get(index), sail, Block.UPDATE_ALL),
                    "could not add runtime mini windmill sail " + index);
        }

        helper.runAfterDelay(5, () -> {
            Object movedAfterAdd = invokeNoArgs(bearing, "getMovedContraption");
            check(movedAfterAdd == moved, "adding mini sails recreated the windmill contraption");
            Object contraptionAfterAdd = invokeNoArgs(movedAfterAdd, "getContraption");
            checkClose(snapshot(contraptionAfterAdd).miniSailPower(), 9.0,
                    "runtime mini-sail addition did not refresh effective contribution");
            check(intValue(invokeNoArgs(contraptionAfterAdd, "getSailBlocks")) == 0,
                    "runtime overlay polluted native sailBlocks");

            // Return to 8.0 then remove one quarter-area base sail -> 7.75, below Create's minimum.
            for (int index = 0; index < 4; index++) {
                check(level.removeBlock(extraMiniCells.get(index), false),
                        "could not remove extra runtime mini sail " + index);
            }
            check(level.removeBlock(baseMiniCells.getFirst(), false),
                    "could not remove threshold-crossing mini sail");

            helper.runAfterDelay(8, () -> {
                check(!booleanValue(invokeNoArgs(bearing, "isRunning")),
                        "windmill remained assembled below minimum effective sail power");
                helper.runAfterDelay(18, () -> {
                    check(!booleanValue(invokeNoArgs(bearing, "isRunning")),
                            "windmill entered an automatic reassembly/disassembly loop");
                    helper.succeed();
                });
            });
        });
    }

    /**
     * One Frame full of eight minis is exactly 2.0 sail power. This exercises Aero's initial
     * validation, fractional layer geometry, runtime below-minimum policy, stress cache, obstruction
     * independence, recovery without reassembly, and transient-overlay reconstruction.
     */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void propellerUsesFractionalMiniGeometryAndRecoversDynamically(GameTestHelper helper) {
        if (!ModList.get().isLoaded("aeronautics")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos bearingPos = helper.absolutePos(new BlockPos(3, 4, 4));
        BlockPos frame = bearingPos.east();
        placeFrame(level, frame);
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(frame)
                .orElseThrow(() -> new AssertionError("missing propeller Frame assembly"));
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create propeller mini child");

        BlockState sail = requireBlock("create", "white_sail").defaultBlockState();
        List<BlockPos> minis = fillFrame(level, child, assembly, frame, sail);
        check(minis.size() == 8, "propeller fixture did not create eight mini sails");

        Block propeller = requireBlock("aeronautics", "propeller_bearing");
        check(level.setBlock(bearingPos, facing(propeller.defaultBlockState(), Direction.EAST), Block.UPDATE_ALL),
                "could not place Aeronautics propeller bearing");
        BlockEntity bearing = requireBlockEntity(level, bearingPos);
        invokeNoArgs(bearing, "assemble");
        check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                "propeller did not assemble from exactly 2.0 mini sail power");

        Object moved = invokeNoArgs(bearing, "getMovedContraption");
        check(moved != null, "assembled mini propeller has no moved contraption");
        Object contraption = invokeNoArgs(moved, "getContraption");
        check(intValue(invokeNoArgs(contraption, "getSailBlocks")) == 0,
                "Aeronautics assembly leaked mini sails into native sailBlocks");
        checkClose(floatField(bearing, "totalSailPower"), 2.0,
                "Aeronautics totalSailPower did not include eight quarter-area minis");

        assertFractionalLayers(bearing);
        invoke(bearing, "setRotationSpeed", new Class<?>[]{float.class}, 10.0f);
        double thrustBeforeObstruction = doubleValue(invokeNoArgs(bearing, "getThrust"));
        double airflowBeforeObstruction = doubleValue(invokeNoArgs(bearing, "getAirflow"));
        float stressBefore = floatValue(invokeNoArgs(bearing, "calculateStressApplied"));
        check(thrustBeforeObstruction > 0.0, "2.0 effective sails produced no propeller thrust");
        check(airflowBeforeObstruction > 0.0, "2.0 effective sails produced no propeller airflow");
        check(stressBefore > 0.0, "active mini propeller produced no kinetic stress");
        checkClose(floatField(bearing, "lastStressApplied"), stressBefore,
                "active propeller left stale lastStressApplied");

        BlockPos obstruction = bearingPos.east(5);
        check(level.setBlock(obstruction, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place airflow obstruction control");
        invoke(bearing, "setRotationSpeed", new Class<?>[]{float.class}, 10.0f);
        checkClose(doubleValue(invokeNoArgs(bearing, "getThrust")), thrustBeforeObstruction,
                "world obstruction incorrectly reduced physical propeller thrust");

        check(level.removeBlock(minis.getFirst(), false), "could not remove runtime propeller mini sail");
        helper.runAfterDelay(5, () -> {
            check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                    "propeller destructively disassembled below 2.0 runtime sail power");
            check(invokeNoArgs(bearing, "getMovedContraption") == moved,
                    "propeller contraption was recreated after mini removal");
            checkClose(floatField(bearing, "totalSailPower"), 1.75,
                    "runtime removal did not refresh totalSailPower to 1.75");
            invoke(bearing, "setRotationSpeed", new Class<?>[]{float.class}, 10.0f);
            checkClose(doubleValue(invokeNoArgs(bearing, "getThrust")), 0.0,
                    "below-minimum propeller still produces thrust");
            checkClose(doubleValue(invokeNoArgs(bearing, "getAirflow")), 0.0,
                    "below-minimum propeller still produces airflow");
            checkClose(floatValue(invokeNoArgs(bearing, "calculateStressApplied")), 0.0,
                    "below-minimum inactive propeller still charges kinetic stress");
            checkClose(floatField(bearing, "lastStressApplied"), 0.0,
                    "below-minimum propeller left stale stress cache");

            check(level.setBlock(minis.getFirst(), sail, Block.UPDATE_ALL),
                    "could not restore runtime propeller mini sail");
            helper.runAfterDelay(5, () -> {
                check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                        "propeller failed to stay assembled during mini recovery");
                check(invokeNoArgs(bearing, "getMovedContraption") == moved,
                        "propeller recovery recreated its contraption");
                checkClose(floatField(bearing, "totalSailPower"), 2.0,
                        "propeller did not recover effective sail power after re-add");
                invoke(bearing, "setRotationSpeed", new Class<?>[]{float.class}, 10.0f);
                check(doubleValue(invokeNoArgs(bearing, "getThrust")) > 0.0,
                        "recovered propeller did not restore thrust");
                check(doubleValue(invokeNoArgs(bearing, "getAirflow")) > 0.0,
                        "recovered propeller did not restore airflow");
                check(floatValue(invokeNoArgs(bearing, "calculateStressApplied")) > 0.0,
                        "recovered propeller did not restore kinetic stress");

                // Simulate the transient cache disappearing across a reload/reattach. The child block
                // data remains authoritative and observe() must rebuild the overlay without NBT state.
                CreateMiniSailOverlayManager.forget((com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) bearing);
                ((DynamicMiniSailCarrier) contraption).antikytheramechanism$setMiniSails(DynamicMiniSailSnapshot.EMPTY);
                CreateMiniSailOverlayManager.observe((com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) bearing);
                checkClose(snapshot(contraption).miniSailPower(), 2.0,
                        "transient mini overlay did not reconstruct from managed child content");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void gyroscopicPropellerInheritsDynamicMiniSailsWithoutBreakingTilt(GameTestHelper helper) {
        if (!ModList.get().isLoaded("aeronautics")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos bearingPos = helper.absolutePos(new BlockPos(3, 4, 4));
        BlockPos frame = bearingPos.east();
        placeFrame(level, frame);
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(frame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create gyro mini child");
        BlockState sail = requireBlock("create", "white_sail").defaultBlockState();
        List<BlockPos> minis = fillFrame(level, child, assembly, frame, sail);

        Block gyro = requireBlock("aeronautics", "gyroscopic_propeller_bearing");
        check(level.setBlock(bearingPos, facing(gyro.defaultBlockState(), Direction.EAST), Block.UPDATE_ALL),
                "could not place gyroscopic propeller bearing");
        BlockEntity bearing = requireBlockEntity(level, bearingPos);
        invokeNoArgs(bearing, "assemble");
        check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                "gyro did not assemble from exactly 2.0 mini sail power");
        Object moved = invokeNoArgs(bearing, "getMovedContraption");
        checkClose(floatField(bearing, "totalSailPower"), 2.0,
                "gyro did not inherit effective mini sail power");
        Vector3d initialDirection = vectorField(bearing, "thrustDirection");
        check(initialDirection.isFinite() && initialDirection.lengthSquared() > 0.5,
                "gyro initial thrust direction is invalid");

        check(level.removeBlock(minis.getFirst(), false), "could not remove gyro mini sail");
        helper.runAfterDelay(5, () -> {
            check(booleanValue(invokeNoArgs(bearing, "isRunning")),
                    "gyro destructively disassembled below runtime minimum");
            check(invokeNoArgs(bearing, "getMovedContraption") == moved,
                    "gyro mini removal recreated its contraption");
            checkClose(floatField(bearing, "totalSailPower"), 1.75,
                    "gyro did not refresh mini sail removal");
            check(level.setBlock(minis.getFirst(), sail, Block.UPDATE_ALL),
                    "could not restore gyro mini sail");

            helper.runAfterDelay(5, () -> {
                check(invokeNoArgs(bearing, "getMovedContraption") == moved,
                        "gyro mini recovery recreated its contraption");
                checkClose(floatField(bearing, "totalSailPower"), 2.0,
                        "gyro did not recover mini sail power");
                Vector3d recoveredDirection = vectorField(bearing, "thrustDirection");
                check(recoveredDirection.isFinite() && recoveredDirection.lengthSquared() > 0.5,
                        "gyro tilt/direction state became invalid after dynamic mini refresh");
                helper.succeed();
            });
        });
    }

    private static void assertFractionalLayers(BlockEntity bearing) {
        Object behavior = fieldValue(bearing, "behavior");
        Object value = invokeNoArgs(behavior, "getLayers");
        check(value instanceof List<?>, "propeller behavior did not expose layers");
        List<?> layers = new ArrayList<>((List<?>) value);
        layers.sort(Comparator.comparingDouble(layer -> doubleValue(invokeNoArgs(layer, "offset"))));
        check(layers.size() == 2,
                "one full mini Frame should create two 0.5-separated axial layers, got " + layers.size());

        double radialCenter = Math.sqrt(0.25 * 0.25 + 0.25 * 0.25);
        double expectedInner = radialCenter - 0.25;
        double expectedOuter = radialCenter + 0.25;
        checkClose(doubleValue(invokeNoArgs(layers.get(0), "offset")), 0.75,
                "first mini axial layer was quantized away from 0.75");
        checkClose(doubleValue(invokeNoArgs(layers.get(1), "offset")), 1.25,
                "second mini axial layer was quantized away from 1.25");
        for (Object layer : layers) {
            checkClose(doubleValue(invokeNoArgs(layer, "innerRadius")), expectedInner,
                    "mini propeller inner radius did not use center radius - 0.25");
            checkClose(doubleValue(invokeNoArgs(layer, "outerRadius")), expectedOuter,
                    "mini propeller outer radius did not use center radius + 0.25");
        }
    }

    private static DynamicMiniSailSnapshot snapshot(Object contraption) {
        check(contraption instanceof DynamicMiniSailCarrier,
                "BearingContraption is missing DynamicMiniSailCarrier mixin");
        return ((DynamicMiniSailCarrier) contraption).antikytheramechanism$getMiniSails();
    }

    private static void placeFrame(ServerLevel level, BlockPos pos) {
        check(level.setBlock(pos, ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + pos);
    }

    private static List<BlockPos> fillFrame(
            ServerLevel level,
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame,
            BlockState state) {
        List<BlockPos> cells = frameMiniCells(child, assembly, frame);
        for (BlockPos cell : cells) {
            check(level.setBlock(cell, state, Block.UPDATE_ALL), "could not place mini sail at " + cell);
        }
        return cells;
    }

    private static List<BlockPos> frameMiniCells(
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame) {
        List<BlockPos> cells = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                    cells.add(MechanismSubLevelService.toPlotPosition(child, mini));
                }
            }
        }
        return cells;
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
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            return method.invoke(target, args);
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

    private static Object fieldValue(Object target, String name) {
        Field field = findField(target.getClass(), name);
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("cannot access field " + name, exception);
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new AssertionError("missing field " + type.getName() + "." + name);
    }

    private static float floatField(Object target, String name) {
        return ((Number) fieldValue(target, name)).floatValue();
    }

    private static Vector3d vectorField(Object target, String name) {
        return new Vector3d((Vector3d) fieldValue(target, name));
    }

    private static boolean booleanValue(Object value) {
        return (Boolean) value;
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private static float floatValue(Object value) {
        return ((Number) value).floatValue();
    }

    private static double doubleValue(Object value) {
        return ((Number) value).doubleValue();
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
