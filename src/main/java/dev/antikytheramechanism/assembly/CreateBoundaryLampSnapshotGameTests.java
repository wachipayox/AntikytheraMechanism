package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
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

/** Regression for a carried wall lever and the real mini state behind its Frame face. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampSnapshotGameTests {
    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private CreateBoundaryLampSnapshotGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void poweredCarriedLeverKeepsEveryMiniLampLitAcrossCaptureAndRestore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos[] positions = {
                helper.absolutePos(new BlockPos(2, 3, 2)),
                helper.absolutePos(new BlockPos(8, 3, 2)),
                helper.absolutePos(new BlockPos(2, 3, 8)),
                helper.absolutePos(new BlockPos(8, 3, 8))
        };
        for (int i = 0; i < FACES.length; i++) {
            exerciseFace(helper, level, positions[i], FACES[i]);
        }
        helper.succeed();
    }

    private static void exerciseFace(
            GameTestHelper helper, ServerLevel level, BlockPos framePos, Direction face) {
        BlockState emptyFrame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        helper.assertTrue(level.setBlock(framePos, emptyFrame, Block.UPDATE_ALL),
                "could not place Frame for " + face);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID id = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        helper.assertTrue(child != null && !child.isRemoved(), "could not materialize child for " + face);

        List<BlockPos> lampLocals = new ArrayList<>(4);
        int mask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos physical = physicalBoundaryCell(face, a, b);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly, framePos, physical.getX(), physical.getY(), physical.getZ());
                BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState()
                        .setValue(BlockStateProperties.LIT, true);
                helper.assertTrue(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, lit, Block.UPDATE_ALL),
                        "could not seed lamp for " + face + " at " + local);
                lampLocals.add(local);
                mask |= 1 << MiniCoordinateMapper.cellIndex(
                        physical.getX(), physical.getY(), physical.getZ());
            }
        }
        child.getPlot().updateBoundingBox();
        helper.assertFalse(MechanismSubLevelService.isPhysicallyEmpty(child),
                "seeded child remained empty for " + face);

        BlockState populatedFrame = emptyFrame.setValue(MechanismFrameBlock.EMPTY, false);
        level.setBlock(framePos, populatedFrame, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        helper.assertTrue(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                "Frame block entity missing for " + face);
        ((MechanismFrameBlockEntity) level.getBlockEntity(framePos)).setOccupiedMask(mask);

        BlockPos leverPos = framePos.relative(face);
        BlockState lever = poweredWallLever(face);
        level.setBlock(leverPos, lever, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        helper.assertTrue(level.getBlockState(leverPos).is(Blocks.LEVER),
                "reachable wall lever fixture missing for " + face);
        assertAllLit(helper, child, lampLocals, "before capture on " + face);

        helper.assertTrue(manager.prepareContraptionMoves(
                        level,
                        Map.of(id, Set.of(framePos)),
                        Map.of(id, Map.of(leverPos, lever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture on " + face);
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(id));

        helper.assertTrue(level.removeBlock(framePos, false), "could not extract Frame on " + face);
        helper.assertTrue(level.getBlockState(leverPos).is(Blocks.LEVER),
                "journaled wall lever popped in Frame-AIR window on " + face);
        level.removeBlock(leverPos, false);
        helper.assertTrue(level.getBlockState(leverPos).isAir(), "could not extract lever on " + face);
        assertAllLit(helper, child, lampLocals, "after capture on " + face);

        AssemblyPose start = assembly.poseTarget();
        Quaterniond q = new Quaterniond()
                .rotateY(Math.toRadians(37.0))
                .mul(start.orientation(new Quaterniond()))
                .normalize();
        helper.assertTrue(manager.updatePoseTarget(id, new AssemblyPose(
                        start.anchorX(), start.anchorY(), start.anchorZ(), q.x, q.y, q.z, q.w)),
                "could not set in-flight pose on " + face);
        assertAllLit(helper, child, lampLocals, "in flight on " + face);

        Map<UUID, Set<BlockPos>> targets = Map.of(id, Set.of(framePos));
        Map<UUID, BlockPos> origins = Map.of(id, framePos);
        Map<UUID, AssemblyPose> poses = Map.of(id, AssemblyPose.identityAt(framePos));
        helper.assertTrue(manager.prepareContraptionPlacement(level, targets, origins, poses),
                "could not prepare restore on " + face);

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            level.setBlock(framePos, populatedFrame, Block.UPDATE_ALL);
            level.setBlock(leverPos, lever, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            helper.assertTrue(level.getBlockState(leverPos).is(Blocks.LEVER),
                    "carried lever missing before commit on " + face);
            helper.assertTrue(manager.finalizeContraptionPlacement(level, Set.of(id)),
                    "could not commit restore on " + face);
            helper.assertTrue(manager.pendingContraptionMove(id).isEmpty(),
                    "Create journal survived restore on " + face);
            helper.assertTrue(level.getBlockState(leverPos).is(Blocks.LEVER),
                    "carried lever popped during reconnect on " + face);
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }
        assertAllLit(helper, child, lampLocals, "after restore on " + face);
    }

    private static BlockState poweredWallLever(Direction face) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face)
                .setValue(BlockStateProperties.POWERED, true);
    }

    private static BlockPos physicalBoundaryCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static void assertAllLit(
            GameTestHelper helper, ServerSubLevel child, List<BlockPos> locals, String phase) {
        helper.assertFalse(child.isRemoved(), "managed child removed " + phase);
        for (BlockPos local : locals) {
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            helper.assertTrue(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + local);
            helper.assertTrue(state.getValue(BlockStateProperties.LIT), "mini lamp went dark " + phase + " at " + local);
        }
    }
}
