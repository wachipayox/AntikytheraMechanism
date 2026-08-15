package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MechanismFrameFluidGameTests {
    private MechanismFrameFluidGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void flowingWaterCannotReplaceOrDuplicateFrame(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos waterPos = framePos.west();

        check(level.setBlock(waterPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place floor below water source");
        check(level.setBlock(framePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place floor below Frame");
        check(level.setBlock(framePos, ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                "could not place Mechanism Frame");

        check(level.getBlockState(framePos).blocksMotion(),
                "Mechanism Frame must block fluid occupancy even though its collision shape is a cage");
        check(!level.getBlockState(framePos).canBeReplaced(Fluids.WATER),
                "Mechanism Frame must reject direct water replacement");

        int initialDrops = nearbyFrameDrops(level, framePos);
        check(level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL),
                "could not place adjacent water source");
        level.scheduleTick(waterPos, Fluids.WATER, 1);

        helper.runAfterDelay(30, () -> {
            check(level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get()),
                    "flowing water replaced the Mechanism Frame");
            check(level.getFluidState(framePos).isEmpty(),
                    "Mechanism Frame became fluid-filled/waterlogged");

            int finalDrops = nearbyFrameDrops(level, framePos);
            check(finalDrops == initialDrops,
                    "flowing water duplicated Mechanism Frame drops: before="
                            + initialDrops + ", after=" + finalDrops);
            helper.succeed();
        });
    }

    private static int nearbyFrameDrops(ServerLevel level, BlockPos framePos) {
        AABB area = new AABB(
                framePos.getX() - 3.0,
                framePos.getY() - 3.0,
                framePos.getZ() - 3.0,
                framePos.getX() + 4.0,
                framePos.getY() + 4.0,
                framePos.getZ() + 4.0);
        return level.getEntitiesOfClass(
                        ItemEntity.class,
                        area,
                        entity -> entity.getItem().is(ModRegistries.MECHANISM_FRAME_ITEM.get()))
                .size();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
