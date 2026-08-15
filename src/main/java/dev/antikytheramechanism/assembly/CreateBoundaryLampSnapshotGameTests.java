package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
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

/** Exact regression shape for a powered carried macro lever feeding four mini lamps on its Frame face. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampSnapshotGameTests {
    private static final Direction[] HORIZONTAL_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private CreateBoundaryLampSnapshotGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void poweredCarriedLeverKeepsEveryMiniLampLitAcrossCaptureAndRestore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos[] framePositions = {
                helper.absolutePos(new BlockPos(2, 3, 2)),
                helper.absolutePos(new BlockPos(8, 3, 2)),
                helper.absolutePos(new BlockPos(2, 3, 8)),
                helper.absolutePos(new BlockPos(8, 3, 8))
        };

        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            exerciseFace(level, framePositions[index], HORIZONTAL_FACES[index]);
        }
        helper.succeed();
    }

    private static void exerciseFace(ServerLevel level, BlockPos framePos, Direction physicalFace) {
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL),
                "could not place Frame for face " + physicalFace);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world for " + physicalFace);

        List<BlockPos> lamps = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos local = boundaryCell(assembly, framePos, physicalFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                                global,
                                Blocks.REDSTONE_LAMP.defaultBlockState(),
                                Block.UPDATE_ALL)),
                        "could not place mini lamp on " + physicalFace + " at " + local);
                lamps.add(global);
            }
        }

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
        check(level.setBlock(leverPos, lever, Block.UPDATE_ALL),
                "could not place powered macro lever on " + physicalFace);
        check(level.getBlockState(leverPos).is(Blocks.LEVER),
                "powered macro lever did not survive initial placement on " + physicalFace);

        // Force one exact boundary replay so the test is independent from same-tick scheduler
        // coalescing. Every quadrant of the selected Frame face must see the same carried lever.
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);
        assertAllPoweredAndLit(level, lamps, "before capture on " + physicalFace);

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assemblyId, Set.of(framePos)),
                        Map.of(assemblyId, Map.of(leverPos, lever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture journal on " + physicalFace);
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));

        // Deliberately remove the Frame first. Real Create removal order can expose exactly this AIR
        // interval before a brittle attached block is extracted; the carried lever must not pop off.
        check(level.removeBlock(framePos, false), "could not mirror Create Frame extraction on " + physicalFace);
        check(level.getBlockState(leverPos).is(Blocks.LEVER),
                "carried lever popped when source Frame became AIR on " + physicalFace);
        check(level.removeBlock(leverPos, false), "could not mirror Create lever extraction on " + physicalFace);
        assertAllPoweredAndLit(level, lamps, "after physical capture on " + physicalFace);

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
                "could not enter in-flight Create pose on " + physicalFace);
        assertAllPoweredAndLit(level, lamps, "during in-flight pose on " + physicalFace);

        // Restore at the original snapped pose. Keep CreateAssemblyPlacementContext alive across the
        // commit exactly like Contraption#addBlocksToWorld does: finalize() removes the journal and
        // immediately reconnects neighbours before the wrapper finally unwinds the context.
        Map<UUID, Set<BlockPos>> targets = Map.of(assemblyId, Set.of(framePos));
        Map<UUID, BlockPos> origins = Map.of(assemblyId, framePos);
        Map<UUID, AssemblyPose> poses = Map.of(assemblyId, AssemblyPose.identityAt(framePos));
        check(manager.prepareContraptionPlacement(level, targets, origins, poses),
                "could not prepare Create restore on " + physicalFace);

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            check(level.setBlock(framePos, frameState, Block.UPDATE_ALL),
                    "could not restore Frame on " + physicalFace);
            check(level.setBlock(leverPos, lever, Block.UPDATE_ALL),
                    "could not restore carried lever on " + physicalFace);
            check(level.getBlockState(leverPos).is(Blocks.LEVER),
                    "carried lever failed before Create commit on " + physicalFace);
            check(manager.finalizeContraptionPlacement(level, Set.of(assemblyId)),
                    "could not commit Create restore on " + physicalFace);
            check(manager.pendingContraptionMove(assemblyId).isEmpty(),
                    "Create journal survived restore on " + physicalFace);
            check(level.getBlockState(leverPos).is(Blocks.LEVER),
                    "carried lever popped during post-commit reconnect on " + physicalFace);
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }

        assertAllPoweredAndLit(level, lamps, "after committed restore on " + physicalFace);
    }

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePos,
            Direction physicalFace,
            int a,
            int b) {
        Direction logical = assembly.orientation().toLogical(physicalFace);
        int x;
        int y;
        int z;
        switch (logical.getAxis()) {
            case X -> {
                x = logical == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logical == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logical == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + logical.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
    }

    private static void assertAllPoweredAndLit(ServerLevel level, List<BlockPos> lamps, String phase) {
        for (BlockPos lamp : lamps) {
            boolean powered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lamp));
            check(powered, "mini lamp lost projected macro power " + phase + " at " + lamp);
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
