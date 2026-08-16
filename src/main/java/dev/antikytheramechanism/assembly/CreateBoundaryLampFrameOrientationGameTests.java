package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for mini lamp state across Create capture, motion and rotated docking. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampFrameOrientationGameTests {
    private CreateBoundaryLampFrameOrientationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void eastFacingFrameKeepsAllFrontMiniLampsLitInFlight(GameTestHelper helper) {
        exerciseInFlight(helper, Direction.EAST);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void westFacingFrameKeepsAllFrontMiniLampsLitInFlight(GameTestHelper helper) {
        exerciseInFlight(helper, Direction.WEST);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void northFrameStoppedFacingEastKeepsFilledLampCubePowered(GameTestHelper helper) {
        exerciseRotatedStop(helper, Direction.EAST);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void northFrameStoppedFacingSouthKeepsFilledLampCubePowered(GameTestHelper helper) {
        exerciseRotatedStop(helper, Direction.SOUTH);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void northFrameStoppedFacingWestKeepsFilledLampCubePowered(GameTestHelper helper) {
        exerciseRotatedStop(helper, Direction.WEST);
    }

    private static void exerciseInFlight(GameTestHelper helper, Direction frameFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        placeFrame(level, framePos, frameFacing, true);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        helper.assertTrue(assembly.orientation().front() == frameFacing,
                "assembly did not preserve Frame facing " + frameFacing);
        UUID assemblyId = assembly.id();
        ServerSubLevel child = requireChild(level, assembly);

        List<BlockPos> lampLocals = new ArrayList<>(4);
        int occupiedMask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos physical = physicalBoundaryCell(frameFacing, a, b);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly, framePos, physical.getX(), physical.getY(), physical.getZ());
                placeMiniLampLikeRouter(level, framePos, child, local);
                lampLocals.add(local);
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(
                        physical.getX(), physical.getY(), physical.getZ());
            }
        }
        assertFixtureSynchronized(level, child, framePos, occupiedMask);
        forceLampsLit(child, lampLocals);

        BlockPos leverPos = framePos.relative(frameFacing);
        BlockState lever = establishPoweredWallLever(level, framePos, frameFacing);
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);

        helper.runAfterDelay(2, () -> {
            assertLampsLit(helper, child, lampLocals, "before capture");
            helper.assertTrue(level.getBlockState(leverPos).is(Blocks.LEVER),
                    "carried lever disappeared before capture");

            helper.assertTrue(manager.prepareContraptionMoves(
                            level,
                            Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, Map.of(leverPos, lever)),
                            BlockPos.ZERO,
                            false),
                    "could not prepare Create capture journal");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
            helper.assertTrue(level.removeBlock(framePos, false), "could not mirror Create Frame extraction");
            level.removeBlock(leverPos, false);
            helper.assertTrue(level.getBlockState(leverPos).isAir(), "carried lever survived extraction");
            assertLampsLit(helper, child, lampLocals, "after physical capture");

            AssemblyPose startPose = assembly.poseTarget();
            Quaterniond rotation = new Quaterniond()
                    .rotateY(Math.toRadians(37.0))
                    .mul(startPose.orientation(new Quaterniond()))
                    .normalize();
            helper.assertTrue(manager.updatePoseTarget(assemblyId, new AssemblyPose(
                            startPose.anchorX(), startPose.anchorY(), startPose.anchorZ(),
                            rotation.x, rotation.y, rotation.z, rotation.w)),
                    "could not enter in-flight Create pose");
            assertLampsLit(helper, child, lampLocals, "during in-flight pose");
            helper.succeed();
        });
    }

    private static void exerciseRotatedStop(GameTestHelper helper, Direction targetFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        placeFrame(level, framePos, Direction.NORTH, true);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = assembly.id();
        ServerSubLevel child = requireChild(level, assembly);

        List<BlockPos> lampLocals = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePos, x, y, z);
            placeMiniLampLikeRouter(level, framePos, child, local);
            lampLocals.add(local);
        }
        assertFixtureSynchronized(level, child, framePos, 0xFF);
        forceLampsLit(child, lampLocals);

        BlockPos sourceLeverPos = framePos.north();
        BlockState sourceLever = establishPoweredWallLever(level, framePos, Direction.NORTH);
        MiniWorldEnvironment.parentBlockChanged(level, sourceLeverPos);

        helper.runAfterDelay(2, () -> {
            assertLampsLit(helper, child, lampLocals, "before rotated capture");
            helper.assertTrue(manager.prepareContraptionMoves(level, Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, Map.of(sourceLeverPos, sourceLever)), BlockPos.ZERO, false),
                    "could not prepare rotated-stop capture journal");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
            helper.assertTrue(level.removeBlock(framePos, false), "could not extract Frame for rotated stop");
            level.removeBlock(sourceLeverPos, false);
            helper.assertTrue(level.getBlockState(sourceLeverPos).isAir(), "source lever survived capture");
            assertLampsLit(helper, child, lampLocals, "while captured");

            FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, targetFacing);
            Quaterniond q = targetOrientation.quaternion(new Quaterniond());
            AssemblyPose targetPose = new AssemblyPose(framePos.getX() + .5, framePos.getY() + .5, framePos.getZ() + .5,
                    q.x, q.y, q.z, q.w);
            helper.assertTrue(manager.prepareContraptionPlacement(level, Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, framePos), Map.of(assemblyId, targetPose)),
                    "could not prepare rotated docking journal");

            placeFrame(level, framePos, targetFacing, false);
            BlockPos targetLeverPos = framePos.relative(targetFacing);
            establishPoweredWallLever(level, framePos, targetFacing);
            helper.assertTrue(manager.finalizeContraptionPlacement(level, Set.of(assemblyId)),
                    "could not finalize rotated Create stop");
            helper.assertTrue(manager.pendingContraptionMove(assemblyId).isEmpty(),
                    "Create journal survived successful stop");
            helper.assertTrue(assembly.orientation().front() == targetFacing,
                    "assembly did not commit target facing " + targetFacing);

            MiniWorldEnvironment.parentBlockChanged(level, targetLeverPos);
            helper.runAfterDelay(6, () -> {
                assertLampsLit(helper, child, lampLocals, "after rotated stop at " + targetFacing);
                helper.succeed();
            });
        });
    }

    private static ServerSubLevel requireChild(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        if (child == null || child.isRemoved()) throw new AssertionError("could not materialize managed mini world");
        return child;
    }

    private static void placeMiniLampLikeRouter(
            ServerLevel level,
            BlockPos framePos,
            ServerSubLevel child,
            BlockPos local) {
        BlockItem lampItem = (BlockItem) Blocks.REDSTONE_LAMP.asItem();
        ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(lampItem));
        BlockPos globalTarget = MechanismSubLevelService.toPlotPosition(child, local);
        BlockState before = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        if (!before.canBeReplaced()) {
            throw new AssertionError("mini lamp target was not replaceable at " + local);
        }
        BlockHitResult localHit = new BlockHitResult(
                Vec3.atCenterOf(globalTarget), Direction.UP, globalTarget, false);
        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND)
                .useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, localHit));
        BlockState placed = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        if (!result.consumesAction() || !placed.is(Blocks.REDSTONE_LAMP)) {
            throw new AssertionError("vanilla block-item mini placement failed at " + local);
        }
        MechanismAssemblyManager.get(level).refreshFrame(level, framePos);
    }

    private static void forceLampsLit(ServerSubLevel child, List<BlockPos> locals) {
        for (BlockPos local : locals) {
            BlockState current = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            if (!current.is(Blocks.REDSTONE_LAMP)) {
                throw new AssertionError("cannot light missing mini lamp at " + local);
            }
            BlockState lit = current.setValue(BlockStateProperties.LIT, true);
            if (!current.equals(lit)
                    && !child.getPlot().getEmbeddedLevelAccessor().setBlock(local, lit, Block.UPDATE_ALL)
                    && !child.getPlot().getEmbeddedLevelAccessor().getBlockState(local).equals(lit)) {
                throw new AssertionError("could not establish lit mini lamp at " + local);
            }
        }
    }

    private static void assertFixtureSynchronized(
            ServerLevel level, ServerSubLevel child, BlockPos framePos, int occupiedMask) {
        child.getPlot().updateBoundingBox();
        if (MechanismSubLevelService.isPhysicallyEmpty(child)) {
            throw new AssertionError("vanilla mini placement left managed mini world physically empty");
        }
        BlockState state = level.getBlockState(framePos);
        if (state.getValue(MechanismFrameBlock.EMPTY)) {
            throw new AssertionError("vanilla mini placement left populated Frame marked empty");
        }
        if (!(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity frame)) {
            throw new AssertionError("Frame block entity missing while validating mini placement lifecycle");
        }
        if (frame.getOccupiedMask() != occupiedMask) {
            throw new AssertionError("vanilla mini placement produced occupied mask "
                    + frame.getOccupiedMask() + " instead of " + occupiedMask);
        }
    }

    private static BlockState establishPoweredWallLever(ServerLevel level, BlockPos framePos, Direction face) {
        BlockPos leverPos = framePos.relative(face);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face).setValue(BlockStateProperties.POWERED, true);
        level.setBlock(leverPos, lever, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        BlockState actual = level.getBlockState(leverPos);
        if (!actual.is(Blocks.LEVER) || !actual.getValue(BlockStateProperties.POWERED)) {
            throw new AssertionError("could not establish reachable powered wall lever on " + face);
        }
        return actual;
    }

    private static void placeFrame(ServerLevel level, BlockPos framePos, Direction facing, boolean empty) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(MechanismFrameBlock.EMPTY, empty);
        if (!level.setBlock(framePos, state, Block.UPDATE_ALL) && !level.getBlockState(framePos).equals(state)) {
            throw new AssertionError("could not place Frame facing " + facing);
        }
    }

    private static BlockPos physicalBoundaryCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static void assertLampsLit(GameTestHelper helper, ServerSubLevel child, List<BlockPos> locals, String phase) {
        helper.assertFalse(child.isRemoved(), "managed mini world was removed " + phase);
        for (BlockPos local : locals) {
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            helper.assertTrue(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + local);
            helper.assertTrue(state.getValue(BlockStateProperties.LIT), "mini lamp went dark " + phase + " at " + local);
        }
    }
}
