package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exact-source isolation for Simulated slime/glue/chassis searches started in a Frame child. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiniPhysicsAssemblyBoundaryGameTests {
    private MiniPhysicsAssemblyBoundaryGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void miniAssemblyNeverCrossesIntoMacroOrAnotherSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState frame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        check(level.setBlock(framePos, frame, Block.UPDATE_ALL), "could not place Frame");

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel source = MechanismSubLevelService.ensureForContent(level, assembly);
        check(source != null && !source.isRemoved(), "could not materialize source Frame child");
        BlockPos sourceLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(source, sourceLocal);
        check(level.setBlock(sourceGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed source mini candidate");

        ServerSubLevel otherHalfScale = allocate(level, new Pose3d());
        DetachedMiniPhysicsSubLevelService.markDetached(otherHalfScale);
        BlockPos otherHalfScalePos = otherHalfScale.getPlot().getCenterBlock();
        check(level.setBlock(otherHalfScalePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed other half-scale body");

        ServerSubLevel otherUnitScale = allocate(level, new Pose3d());
        BlockPos otherUnitScalePos = otherUnitScale.getPlot().getCenterBlock();
        check(level.setBlock(otherUnitScalePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed unit-scale foreign body");

        BlockPos macro = helper.absolutePos(new BlockPos(7, 3, 3));
        check(level.setBlock(macro, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed macro candidate");

        check(MiniPhysicsAssemblyContext.begin(level, source), "could not enter mini assembly context");
        try {
            check(MiniPhysicsAssemblyContext.allowsCandidate(
                            level, sourceGlobal, level.getBlockState(sourceGlobal)),
                    "owned source mini block was incorrectly excluded");
            check(!MiniPhysicsAssemblyContext.allowsCandidate(
                            level, macro, level.getBlockState(macro)),
                    "macro block crossed into mini assembly search");
            check(!MiniPhysicsAssemblyContext.allowsCandidate(
                            level, otherHalfScalePos, level.getBlockState(otherHalfScalePos)),
                    "different 0.5 SubLevel crossed into mini assembly search");
            check(!MiniPhysicsAssemblyContext.allowsCandidate(
                            level, otherUnitScalePos, level.getBlockState(otherUnitScalePos)),
                    "unit-scale foreign SubLevel crossed into mini assembly search");
        } finally {
            MiniPhysicsAssemblyContext.end();
        }
        helper.succeed();
    }

    private static ServerSubLevel allocate(ServerLevel level, Pose3d pose) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
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
