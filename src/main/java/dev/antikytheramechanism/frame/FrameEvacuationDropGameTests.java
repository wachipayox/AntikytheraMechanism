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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameEvacuationDropGameTests {
    private FrameEvacuationDropGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void playerBreakingFrameByHandRecoversBasicMiniBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos frameRelative = new BlockPos(4, 3, 4);
        BlockPos framePos = helper.absolutePos(frameRelative);
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place Mechanism Frame");

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel subLevel = MechanismSubLevelService.ensureForContent(level, assembly);
        check(subLevel != null && !subLevel.isRemoved(), "could not materialize Frame mini world");

        BlockPos stoneLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos redstoneLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 1, 0);
        BlockPos stoneGlobal = MechanismSubLevelService.toPlotPosition(subLevel, stoneLocal);
        BlockPos redstoneGlobal = MechanismSubLevelService.toPlotPosition(subLevel, redstoneLocal);

        check(level.setBlock(stoneGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini stone");
        check(level.setBlock(redstoneGlobal, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini redstone wire");

        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        check(!level.getBlockState(stoneGlobal).canHarvestBlock(level, stoneGlobal, player),
                "regression setup requires stone to be non-harvestable by an empty hand");

        helper.breakBlock(frameRelative, ItemStack.EMPTY, player);

        helper.runAfterDelay(3, () -> {
            helper.assertItemEntityPresent(Items.COBBLESTONE);
            helper.assertItemEntityPresent(Items.REDSTONE);
            helper.succeed();
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
