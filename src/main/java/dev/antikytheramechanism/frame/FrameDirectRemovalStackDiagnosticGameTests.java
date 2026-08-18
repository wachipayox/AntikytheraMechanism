package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameDirectRemovalStackDiagnosticGameTests {
    private FrameDirectRemovalStackDiagnosticGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void diagnosticMiniSeedOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, framePos);
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize child");
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        try {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] MINI_SEED_BEGIN frame={} local={}", framePos, miniLocal);
            boolean changed = child.getPlot().getEmbeddedLevelAccessor().setBlock(
                    miniLocal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] MINI_SEED_RETURN changed={}", changed);
            check(changed, "mini seed returned false");
            helper.succeed();
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] MINI_SEED_THROW", exception);
            throw exception;
        }
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void diagnosticDirectAirWithoutChild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(6, 3, 3));
        placeFrame(level, framePos);
        try {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_NO_CHILD_BEGIN frame={}", framePos);
            boolean changed = level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_NO_CHILD_RETURN changed={}", changed);
            check(changed, "direct AIR without child returned false");
            helper.succeed();
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_NO_CHILD_THROW", exception);
            throw exception;
        }
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void diagnosticDirectAirWithEmptyChild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(9, 3, 3));
        placeFrame(level, framePos);
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize empty child");
        try {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_EMPTY_CHILD_BEGIN frame={}", framePos);
            boolean changed = level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_EMPTY_CHILD_RETURN changed={}", changed);
            check(changed, "direct AIR with empty child returned false");
            helper.succeed();
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("[DIRECT-AIR-DIAG] AIR_EMPTY_CHILD_THROW", exception);
            throw exception;
        }
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
