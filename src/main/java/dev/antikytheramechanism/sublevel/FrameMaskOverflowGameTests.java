package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for successful writes which overflow a managed FrameMask. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameMaskOverflowGameTests {
    private FrameMaskOverflowGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void successfulOutsideMaskWriteDropsPhysicallyAndIsForceCleared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(5, 3, 5));
        placeFrame(level, framePos);

        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssemblyAt(framePos)
                .orElseThrow(() -> new AssertionError("Frame was not assigned an assembly"));
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        BlockPos edgeMini = MiniCoordinateMapper.frameToMini(assembly, framePos, 1, 0, 0);
        BlockPos overflowMini = edgeMini.east();
        check(!MiniCoordinateMapper.isOwnedMiniPosition(assembly, overflowMini),
                "overflow fixture accidentally selected an owned mini cell");
        BlockPos overflowGlobal = MechanismSubLevelService.toPlotPosition(child, overflowMini);
        Vec3 physicalDropPosition = child.logicalPose().transformPosition(Vec3.atCenterOf(overflowGlobal));

        // The important contract is that this is a real successful write, not a rejected attempt
        // which some placement engine can mistake for success. Post-tick recovery owns it from here.
        check(level.setBlock(overflowGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "outside-mask write was rejected instead of becoming recoverable overflow");
        check(level.getBlockState(overflowGlobal).is(Blocks.STONE),
                "outside-mask block was not materially present before post-tick recovery");

        helper.runAfterDelay(2, () -> {
            check(level.getBlockState(overflowGlobal).isAir(),
                    "post-tick overflow recovery did not force-clear the mini plot cell");
            check(hasItemNear(level, physicalDropPosition, Items.COBBLESTONE),
                    "successful outside-mask stone placement did not produce its physical cobblestone drop");
            helper.succeed();
        });
    }

    private static boolean hasItemNear(ServerLevel level, Vec3 center, Item item) {
        AABB area = new AABB(
                center.x - 1.0,
                center.y - 1.0,
                center.z - 1.0,
                center.x + 1.0,
                center.y + 1.0,
                center.z + 1.0);
        return !level.getEntitiesOfClass(
                        ItemEntity.class,
                        area,
                        entity -> entity.getItem().is(item))
                .isEmpty();
    }

    private static void placeFrame(ServerLevel level, BlockPos pos) {
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(pos, frameState, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
