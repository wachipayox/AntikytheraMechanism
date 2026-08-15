package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Regression for slime/glue selections that mix a detached Antikythera body with macro-scale content. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DetachedMixedScaleAssemblyGameTests {
    private DetachedMixedScaleAssemblyGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void detachedHalfScaleCannotAssembleWithUnitScaleSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel detached = allocate(level, 0.5);
        DetachedMiniPhysicsSubLevelService.markDetached(detached);
        ServerSubLevel unit = allocate(level, 1.0);

        BlockPos detachedPos = detached.getPlot().getCenterBlock();
        BlockPos unitPos = unit.getPlot().getCenterBlock();
        check(level.setBlock(detachedPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed detached 0.5 source block");
        check(level.setBlock(unitPos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed unit-scale source block");

        UUID detachedId = detached.getUniqueId();
        UUID unitId = unit.getUniqueId();
        BoundingBox3i bounds = Objects.requireNonNull(BoundingBox3i.from(List.of(detachedPos, unitPos)));
        ServerSubLevel result = SubLevelAssemblyHelper.assembleBlocks(
                level,
                detachedPos,
                List.of(detachedPos, unitPos),
                bounds);

        check(result == null, "mixed 0.5/1.0 assembly unexpectedly produced a new SubLevel");
        check(!detached.isRemoved() && detachedId.equals(detached.getUniqueId()),
                "detached 0.5 source body was removed or replaced after rejected assembly");
        check(!unit.isRemoved() && unitId.equals(unit.getUniqueId()),
                "unit-scale source body was removed or replaced after rejected assembly");
        check(level.getBlockState(detachedPos).is(Blocks.STONE),
                "detached 0.5 source block disappeared after rejected assembly");
        check(level.getBlockState(unitPos).is(Blocks.COBBLESTONE),
                "unit-scale source block disappeared after rejected assembly");
        check(DetachedMiniPhysicsSubLevelService.isDetached(detached),
                "detached identity was lost after rejected assembly");
        check(DetachedMiniPhysicsSubLevelService.hasHalfScale(detached),
                "detached source no longer has invariant scale 0.5");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void detachedHalfScaleCannotAssembleWithMacroRootBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel detached = allocate(level, 0.5);
        DetachedMiniPhysicsSubLevelService.markDetached(detached);
        BlockPos detachedPos = detached.getPlot().getCenterBlock();
        BlockPos macroPos = helper.absolutePos(new BlockPos(6, 3, 6));

        check(level.setBlock(detachedPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed detached 0.5 source block");
        check(level.setBlock(macroPos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed macro source block");

        UUID detachedId = detached.getUniqueId();
        BoundingBox3i bounds = Objects.requireNonNull(BoundingBox3i.from(List.of(detachedPos, macroPos)));
        ServerSubLevel result = SubLevelAssemblyHelper.assembleBlocks(
                level,
                detachedPos,
                List.of(detachedPos, macroPos),
                bounds);

        check(result == null, "mixed detached/root assembly unexpectedly produced a new SubLevel");
        check(!detached.isRemoved() && detachedId.equals(detached.getUniqueId()),
                "detached source body was removed after rejected root-world assembly");
        check(level.getBlockState(detachedPos).is(Blocks.STONE),
                "detached source block disappeared after rejected root-world assembly");
        check(level.getBlockState(macroPos).is(Blocks.COBBLESTONE),
                "macro source block disappeared after rejected root-world assembly");
        helper.succeed();
    }

    private static ServerSubLevel allocate(ServerLevel level, double scale) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        Pose3d pose = new Pose3d();
        pose.scale().set(scale, scale, scale);
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
