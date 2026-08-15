package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact regression shape for a powered carried macro lever feeding four mini lamps on its Frame face. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampSnapshotGameTests {
    private CreateBoundaryLampSnapshotGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void poweredCarriedLeverKeepsAllFourMiniLampsLitDuringCapture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        List<BlockPos> lamps = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                BlockPos local = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, 1);
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                                global,
                                Blocks.REDSTONE_LAMP.defaultBlockState(),
                                Block.UPDATE_ALL)),
                        "could not place mini lamp " + local);
                lamps.add(global);
            }
        }

        BlockPos leverPos = framePos.south();
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(leverPos, lever, Block.UPDATE_ALL), "could not place powered macro lever");

        // Force one exact boundary replay so this test is independent from the scheduler's same-tick
        // coalescing. The normal live bridge must light all four quadrants before Create owns them.
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);
        assertAllLit(level, lamps, "before capture");

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assembly.id(), Set.of(framePos)),
                        Map.of(assembly.id(), Map.of(leverPos, lever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture journal");
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assembly.id()));

        check(level.removeBlock(framePos, false), "could not mirror Create Frame extraction");
        check(level.removeBlock(leverPos, false), "could not mirror Create lever extraction");
        assertAllLit(level, lamps, "after physical capture");

        AssemblyPose startPose = assembly.poseTarget();
        Quaterniond inFlightRotation = new Quaterniond()
                .rotateY(Math.toRadians(37.0))
                .mul(startPose.orientation(new Quaterniond()))
                .normalize();
        check(manager.updatePoseTarget(assembly.id(), new AssemblyPose(
                        startPose.anchorX(),
                        startPose.anchorY(),
                        startPose.anchorZ(),
                        inFlightRotation.x,
                        inFlightRotation.y,
                        inFlightRotation.z,
                        inFlightRotation.w)),
                "could not enter in-flight Create pose");

        for (BlockPos lamp : lamps) {
            boolean powered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lamp));
            check(powered, "captured powered lever stopped projecting signal to lamp " + lamp);
        }
        assertAllLit(level, lamps, "during in-flight pose");
        helper.succeed();
    }

    private static void assertAllLit(ServerLevel level, List<BlockPos> lamps, String phase) {
        for (BlockPos lamp : lamps) {
            BlockState state = level.getBlockState(lamp);
            check(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + lamp);
            check(state.getValue(BlockStateProperties.LIT), "mini lamp went dark " + phase + " at " + lamp);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
