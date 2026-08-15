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
import java.util.UUID;

/** Regression for carried macro power while the Frame itself starts EAST/WEST. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampFrameOrientationGameTests {
    private CreateBoundaryLampFrameOrientationGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void eastFacingFrameKeepsAllFrontMiniLampsLitInFlight(GameTestHelper helper) {
        exercise(helper, Direction.EAST);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void westFacingFrameKeepsAllFrontMiniLampsLitInFlight(GameTestHelper helper) {
        exercise(helper, Direction.WEST);
    }

    private static void exercise(GameTestHelper helper, Direction frameFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, frameFacing)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL),
                "could not place " + frameFacing + " Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        check(assembly.orientation().front() == frameFacing,
                "assembly did not preserve Frame facing " + frameFacing + ": " + assembly.orientation());
        UUID assemblyId = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        Direction physicalFace = frameFacing;
        Direction logicalFace = assembly.orientation().toLogical(physicalFace);
        List<BlockPos> lamps = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos local = boundaryCell(assembly, framePos, logicalFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                                global,
                                Blocks.REDSTONE_LAMP.defaultBlockState(),
                                Block.UPDATE_ALL)),
                        "could not place front mini lamp " + local);
                lamps.add(global);
            }
        }

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(leverPos, lever, Block.UPDATE_ALL), "could not place powered carried lever");
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);
        assertAllPoweredAndLit(level, lamps, "before capture", frameFacing);

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assemblyId, Set.of(framePos)),
                        Map.of(assemblyId, Map.of(leverPos, lever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture journal");
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
        check(level.removeBlock(framePos, false), "could not mirror Create Frame extraction");
        check(level.removeBlock(leverPos, false), "could not mirror Create lever extraction");
        assertAllPoweredAndLit(level, lamps, "after physical capture", frameFacing);

        AssemblyPose startPose = assembly.poseTarget();
        Quaterniond inFlightRotation = new Quaterniond()
                .rotateY(Math.toRadians(37.0))
                .mul(startPose.orientation(new Quaterniond()))
                .normalize();
        check(manager.updatePoseTarget(assemblyId, new AssemblyPose(
                        startPose.anchorX(),
                        startPose.anchorY(),
                        startPose.anchorZ(),
                        inFlightRotation.x,
                        inFlightRotation.y,
                        inFlightRotation.z,
                        inFlightRotation.w)),
                "could not enter in-flight Create pose");
        assertAllPoweredAndLit(level, lamps, "during in-flight pose", frameFacing);
        helper.succeed();
    }

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePos,
            Direction logicalFace,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (logicalFace.getAxis()) {
            case X -> {
                x = logicalFace == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logicalFace == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logicalFace == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + logicalFace.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
    }

    private static void assertAllPoweredAndLit(
            ServerLevel level,
            List<BlockPos> lamps,
            String phase,
            Direction frameFacing) {
        for (BlockPos lamp : lamps) {
            boolean powered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lamp));
            check(powered, frameFacing + " Frame mini lamp lost macro power " + phase + " at " + lamp);
            BlockState state = level.getBlockState(lamp);
            check(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + lamp);
            check(state.getValue(BlockStateProperties.LIT),
                    frameFacing + " Frame mini lamp went dark " + phase + " at " + lamp);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
