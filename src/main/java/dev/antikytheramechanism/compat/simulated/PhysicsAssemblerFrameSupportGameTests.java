package dev.antikytheramechanism.compat.simulated;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Regression coverage for Simulated's any-surface attachment semantics on HIDDEN Frames. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicsAssemblerFrameSupportGameTests {
    private static final String ASSEMBLER_BLOCK =
            "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlock";

    private PhysicsAssemblerFrameSupportGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void hiddenFrameAssemblerUsesAnyMiniSurfaceOnContactFace(GameTestHelper helper) {
        Method canAttach = resolveCanAttach();
        if (canAttach == null) {
            // CORE validation intentionally runs without Simulated. The focused Simulated job exercises
            // this same test with the optional target present and the mixin applied.
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 4, 4));
        check(level.setBlock(frame, ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                "could not place Mechanism Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(frame)
                .orElseThrow(() -> new AssertionError("missing Mechanism Frame assembly"));
        BlockPos assemblerPosition = frame.east();
        Direction directionToSupport = Direction.WEST;

        check(invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "visible Frame cage no longer provides Simulated's native tiny support surface");

        check(manager.setFrameShellMode(level, frame, FrameShellMode.HIDDEN),
                "could not hide Frame for support test");
        check(!invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "empty HIDDEN Frame incorrectly supports a Physics Assembler");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create managed mini child");

        BlockPos eastMini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, 1, 0, 0);
        BlockPos eastGlobal = MechanismSubLevelService.toPlotPosition(child, eastMini);
        check(level.setBlock(eastGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place one mini support cell on contacted Frame face");
        check(invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "one mini block on the contacted face did not support the HIDDEN Frame assembler");

        check(level.removeBlock(eastGlobal, false), "could not remove contacted mini support cell");
        BlockPos westMini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, 0, 0, 0);
        BlockPos westGlobal = MechanismSubLevelService.toPlotPosition(child, westMini);
        check(level.setBlock(westGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place control mini cell on opposite face");
        check(!invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "mini content on the opposite Frame face incorrectly supports the assembler");

        check(level.removeBlock(westGlobal, false), "could not clear opposite-face control cell");
        check(level.setBlock(eastGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not restore contacted mini support cell");
        check(invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "restored contacted mini support did not restore assembler attachment");
        check(level.removeBlock(eastGlobal, false), "could not remove restored mini support cell");
        check(!invokeCanAttach(canAttach, level, assemblerPosition, directionToSupport),
                "assembler support did not disappear when its last contacted mini surface was removed");

        helper.succeed();
    }

    private static Method resolveCanAttach() {
        try {
            Class<?> type = Class.forName(ASSEMBLER_BLOCK);
            Method method = type.getDeclaredMethod(
                    "canAttach", LevelReader.class, BlockPos.class, Direction.class);
            method.setAccessible(true);
            return method;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Simulated PhysicsAssemblerBlock.canAttach signature changed", exception);
        }
    }

    private static boolean invokeCanAttach(
            Method method,
            LevelReader level,
            BlockPos assemblerPosition,
            Direction directionToSupport) {
        try {
            return (Boolean) method.invoke(null, level, assemblerPosition, directionToSupport);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("could not invoke PhysicsAssemblerBlock.canAttach", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("PhysicsAssemblerBlock.canAttach threw", cause);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
