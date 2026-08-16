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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos[] framePositions = {
                helper.absolutePos(new BlockPos(2, 3, 2)),
                helper.absolutePos(new BlockPos(8, 3, 2)),
                helper.absolutePos(new BlockPos(2, 3, 8)),
                helper.absolutePos(new BlockPos(8, 3, 8))
        };

        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            exerciseFace(helper, player, level, framePositions[index], HORIZONTAL_FACES[index]);
        }
        helper.succeed();
    }

    private static void exerciseFace(
            GameTestHelper helper,
            Player player,
            ServerLevel level,
            BlockPos framePos,
            Direction physicalFace) {
        BlockState emptyFrameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(framePos, emptyFrameState, Block.UPDATE_ALL),
                "could not place Frame for face " + physicalFace);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world for " + physicalFace);

        List<BlockPos> lampLocals = new ArrayList<>(4);
        int occupiedMask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos physicalCell = physicalBoundaryCell(physicalFace, a, b);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly,
                        framePos,
                        physicalCell.getX(),
                        physicalCell.getY(),
                        physicalCell.getZ());
                BlockState litLamp = Blocks.REDSTONE_LAMP.defaultBlockState()
                        .setValue(BlockStateProperties.LIT, true);
                check(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, litLamp, Block.UPDATE_ALL),
                        "could not seed mini lamp on " + physicalFace + " at " + local);
                lampLocals.add(local);
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(
                        physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
            }
        }
        child.getPlot().updateBoundingBox();
        check(!MechanismSubLevelService.isPhysicallyEmpty(child),
                "seeded child remained physically empty on " + physicalFace);

        BlockState populatedFrameState = emptyFrameState.setValue(MechanismFrameBlock.EMPTY, false);
        check(level.setBlock(framePos, populatedFrameState, Block.UPDATE_ALL),
                "could not mark Frame populated on " + physicalFace);
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                "Frame block entity missing on " + physicalFace);
        ((MechanismFrameBlockEntity) level.getBlockEntity(framePos)).setOccupiedMask(occupiedMask);

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = placePoweredWallLeverLikePlayer(helper, player, framePos, physicalFace);
        assertAllLit(child, lampLocals, "before capture on " + physicalFace);

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assemblyId, Set.of(framePos)),
                        Map.of(assemblyId, Map.of(leverPos, lever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture journal on " + physicalFace);
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));

        // This is the actual subject of the regression: while Create has removed the Frame first, the
        // journaled mini-backed support must keep the carried wall lever alive until Create extracts it.
        check(level.removeBlock(framePos, false), "could not mirror Create Frame extraction on " + physicalFace);
        check(level.getBlockState(leverPos).is(Blocks.LEVER),
                "carried lever popped when source Frame became AIR on " + physicalFace);
        level.removeBlock(leverPos, false);
        check(level.getBlockState(leverPos).isAir(), "could not mirror Create lever extraction on " + physicalFace);
        assertAllLit(child, lampLocals, "after physical capture on " + physicalFace);

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
        assertAllLit(child, lampLocals, "during in-flight pose on " + physicalFace);

        Map<UUID, Set<BlockPos>> targets = Map.of(assemblyId, Set.of(framePos));
        Map<UUID, BlockPos> origins = Map.of(assemblyId, framePos);
        Map<UUID, AssemblyPose> poses = Map.of(assemblyId, AssemblyPose.identityAt(framePos));
        check(manager.prepareContraptionPlacement(level, targets, origins, poses),
                "could not prepare Create restore on " + physicalFace);

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            check(level.setBlock(framePos, populatedFrameState, Block.UPDATE_ALL),
                    "could not restore populated Frame on " + physicalFace);
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

        assertAllLit(child, lampLocals, "after committed restore on " + physicalFace);
    }

    private static BlockState placePoweredWallLeverLikePlayer(
            GameTestHelper helper,
            Player player,
            BlockPos framePos,
            Direction physicalFace) {
        ServerLevel level = helper.getLevel();
        ItemStack stack = new ItemStack(Blocks.LEVER);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Vec3 hitLocation = Vec3.atCenterOf(framePos).add(
                physicalFace.getStepX() * .5,
                physicalFace.getStepY() * .5,
                physicalFace.getStepZ() * .5);
        BlockHitResult hit = new BlockHitResult(hitLocation, physicalFace, framePos, false);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(result.consumesAction(), "vanilla wall-lever placement failed on " + physicalFace);

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState placed = level.getBlockState(leverPos);
        check(placed.is(Blocks.LEVER), "wall lever did not appear on " + physicalFace);
        check(level.setBlock(leverPos, placed.setValue(BlockStateProperties.POWERED, true), Block.UPDATE_ALL),
                "could not switch wall lever on " + physicalFace);
        BlockState powered = level.getBlockState(leverPos);
        check(powered.is(Blocks.LEVER) && powered.getValue(BlockStateProperties.POWERED),
                "wall lever did not remain powered on " + physicalFace);
        return powered;
    }

    private static BlockPos physicalBoundaryCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static void assertAllLit(ServerSubLevel child, List<BlockPos> lampLocals, String phase) {
        check(!child.isRemoved(), "managed mini world was removed " + phase);
        for (BlockPos local : lampLocals) {
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            check(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + local);
            check(state.getValue(BlockStateProperties.LIT), "mini lamp went dark " + phase + " at " + local);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
