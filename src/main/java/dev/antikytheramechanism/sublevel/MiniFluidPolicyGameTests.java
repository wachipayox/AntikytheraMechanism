package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiniFluidPolicyGameTests {
    private MiniFluidPolicyGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void deniedWaterBucketCannotPlaceOrWaterlogFrameChild(GameTestHelper helper) {
        check(!MiniaturizableRegistry.isAllowed(Fluids.WATER),
                "water unexpectedly became whitelisted in the default GameTest policy");

        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        check(level.setBlock(framePos, ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                "could not place Mechanism Frame");

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize Frame child");

        BlockPos fluidLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos slabLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 1, 0, 0);
        BlockPos fluidGlobal = MechanismSubLevelService.toPlotPosition(child, fluidLocal);
        BlockPos slabGlobal = MechanismSubLevelService.toPlotPosition(child, slabLocal);

        check(!emptyWaterBucket(level, fluidGlobal), "denied water bucket placed a source inside Frame child");
        check(level.getFluidState(fluidGlobal).isEmpty(), "denied bucket leaked water into Frame child");

        check(level.setBlock(slabGlobal, Blocks.OAK_SLAB.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed waterloggable mini slab");
        check(!emptyWaterBucket(level, slabGlobal), "denied water bucket reported successful waterlogging");
        check(!level.getBlockState(slabGlobal).getValue(BlockStateProperties.WATERLOGGED),
                "denied water bucket waterlogged a Frame-child block");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void deniedWaterBucketCannotPlaceOrWaterlogDetachedBody(GameTestHelper helper) {
        check(!MiniaturizableRegistry.isAllowed(Fluids.WATER),
                "water unexpectedly became whitelisted in the default GameTest policy");

        ServerLevel level = helper.getLevel();
        ServerSubLevel detached = allocateHalfScaleBody(level);
        DetachedMiniPhysicsSubLevelService.markDetached(detached);

        BlockPos fluidGlobal = detached.getPlot().getCenterBlock();
        BlockPos slabGlobal = fluidGlobal.east();
        check(!emptyWaterBucket(level, fluidGlobal), "denied water bucket placed a source in detached body");
        check(level.getFluidState(fluidGlobal).isEmpty(), "denied bucket leaked water into detached body");

        check(level.setBlock(slabGlobal, Blocks.OAK_SLAB.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed detached waterloggable slab");
        check(!emptyWaterBucket(level, slabGlobal), "denied water bucket reported detached waterlogging");
        check(!level.getBlockState(slabGlobal).getValue(BlockStateProperties.WATERLOGGED),
                "denied water bucket waterlogged a detached mini block");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void fluidWhitelistUsesLegacyLiquidBlockPolicy(GameTestHelper helper) {
        check(MiniaturizableRegistry.status(Fluids.WATER) == MiniaturizableRegistry.status(Blocks.WATER),
                "water fluid policy diverged from minecraft:water block whitelist policy");
        check(MiniaturizableRegistry.status(Fluids.LAVA) == MiniaturizableRegistry.status(Blocks.LAVA),
                "lava fluid policy diverged from minecraft:lava block whitelist policy");
        check(MiniaturizableRegistry.isAllowed(Fluids.EMPTY), "empty bucket fluid must never be blocked");
        helper.succeed();
    }

    private static boolean emptyWaterBucket(ServerLevel level, BlockPos position) {
        BucketItem waterBucket = (BucketItem) Items.WATER_BUCKET;
        return waterBucket.emptyContents(
                null,
                level,
                position,
                null,
                new ItemStack(Items.WATER_BUCKET));
    }

    private static ServerSubLevel allocateHalfScaleBody(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        Pose3d pose = new Pose3d();
        pose.scale().set(
                MiniCoordinateMapper.SUBLEVEL_SCALE,
                MiniCoordinateMapper.SUBLEVEL_SCALE,
                MiniCoordinateMapper.SUBLEVEL_SCALE);
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        subLevel.getPlot().newEmptyChunk(subLevel.getPlot().getCenterChunk());
        return subLevel;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
