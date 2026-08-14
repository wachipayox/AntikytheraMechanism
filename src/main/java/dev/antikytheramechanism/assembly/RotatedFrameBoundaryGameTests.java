package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.antikytheramechanism.sublevel.RedstoneBoundaryRefreshScheduler;
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
import org.joml.Quaterniond;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for the live macro -> mini boundary after a Create-style rotation commit. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RotatedFrameBoundaryGameTests {
    private RotatedFrameBoundaryGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void rotatedDisassembledFrameKeepsListeningToLaterSupportChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos target = helper.absolutePos(new BlockPos(8, 3, 3));
        placeFrame(level, source, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, source);
        UUID id = assembly.id();
        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, Direction.EAST);

        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(source)), BlockPos.ZERO, false),
                "capture preflight failed");
        level.removeBlock(source, false);
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(target)),
                        Map.of(id, target),
                        Map.of(id, poseAt(target, targetOrientation))),
                "placement preflight failed");
        placeFrame(level, target, targetOrientation.front());
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "placement commit failed");
        check(manager.pendingContraptionMove(id).isEmpty(), "journal survived successful commit");

        assembly = requireAssembly(manager, target);
        check(assembly.orientation().equals(targetOrientation), "rotated assembly orientation was not committed");

        BlockPos floor = target.below();
        check(level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place post-disassembly support floor");
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, target, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState();
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(miniGlobal, dust, Block.UPDATE_ALL)),
                "could not place mini redstone dust");
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.getBlockState(miniGlobal).canSurvive(level, miniGlobal)),
                "mini dust did not see the post-disassembly support floor");

        check(level.removeBlock(floor, false), "could not remove post-disassembly support floor");

        // Parent topology writes are intentionally deferred to the Frame's scheduled tick. Execute
        // that exact queue here so the test verifies both that the later parent write was recorded
        // and that a rotated, docked Frame accepts the refresh after Create has disassembled it.
        RedstoneBoundaryRefreshScheduler.runScheduled(level, target);

        check(level.getBlockState(miniGlobal).isAir(),
                "rotated disassembled Frame stopped propagating later support changes to mini blocks");
        helper.succeed();
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond q = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(
                origin.getX() + .5,
                origin.getY() + .5,
                origin.getZ() + .5,
                q.x, q.y, q.z, q.w);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static MechanismAssembly requireAssembly(MechanismAssemblyManager manager, BlockPos pos) {
        return manager.getAssemblyAt(pos)
                .orElseThrow(() -> new AssertionError("missing assembly at " + pos));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
