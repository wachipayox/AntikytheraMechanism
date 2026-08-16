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

/** Regression coverage for lit mini content across Create capture, motion and rotated docking. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateBoundaryLampFrameOrientationGameTests {
    private CreateBoundaryLampFrameOrientationGameTests() {
    }

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

        List<BlockPos> lampLocals = new ArrayList<>(4);
        int occupiedMask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos physicalCell = physicalBoundaryCell(frameFacing, a, b);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly, framePos, physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
                seedLitLamp(child, local);
                lampLocals.add(local);
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(
                        physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
            }
        }
        synchronizeFixtureFrame(level, child, framePos, occupiedMask, "front lamp fixture");

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos sourcePos = framePos.relative(frameFacing);
        BlockState sourceState = placePoweredWallLeverLikePlayer(helper, player, framePos, frameFacing);

        helper.runAfterDelay(2, () -> {
            assertLampsLit(helper, child, lampLocals, "before capture");
            helper.assertTrue(level.getBlockState(sourcePos).is(Blocks.LEVER)
                            && level.getBlockState(sourcePos).getValue(BlockStateProperties.POWERED),
                    "powered wall lever disappeared before capture");

            helper.assertTrue(manager.prepareContraptionMoves(
                            level,
                            Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, Map.of(sourcePos, sourceState)),
                            BlockPos.ZERO,
                            false),
                    "could not prepare Create capture journal");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
            helper.assertTrue(level.removeBlock(framePos, false), "could not mirror Create Frame extraction");
            level.removeBlock(sourcePos, false);
            helper.assertTrue(level.getBlockState(sourcePos).isAir(), "carried lever survived physical extraction");

            // During capture/motion macro-mini bridges are intentionally quiescent. The regression
            // therefore checks preservation of the real child block state, not a live macro signal.
            assertLampsLit(helper, child, lampLocals, "after physical capture");

            AssemblyPose startPose = assembly.poseTarget();
            Quaterniond inFlightRotation = new Quaterniond()
                    .rotateY(Math.toRadians(37.0))
                    .mul(startPose.orientation(new Quaterniond()))
                    .normalize();
            helper.assertTrue(manager.updatePoseTarget(assemblyId, new AssemblyPose(
                            startPose.anchorX(),
                            startPose.anchorY(),
                            startPose.anchorZ(),
                            inFlightRotation.x,
                            inFlightRotation.y,
                            inFlightRotation.z,
                            inFlightRotation.w)),
                    "could not enter in-flight Create pose");
            assertLampsLit(helper, child, lampLocals, "during in-flight pose");
            helper.succeed();
        });
    }

    private static void exerciseRotatedStop(GameTestHelper helper, Direction targetFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState sourceFrame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(framePos, sourceFrame, Block.UPDATE_ALL), "could not place default NORTH Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        List<BlockPos> lampLocals = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos local = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
                    seedLitLamp(child, local);
                    lampLocals.add(local);
                }
            }
        }
        synchronizeFixtureFrame(level, child, framePos, 0xFF, "filled lamp cube fixture");

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos sourcePos = framePos.north();
        BlockState sourceState = placePoweredWallLeverLikePlayer(helper, player, framePos, Direction.NORTH);

        helper.runAfterDelay(2, () -> {
            assertLampsLit(helper, child, lampLocals, "before rotated capture");

            helper.assertTrue(manager.prepareContraptionMoves(
                            level,
                            Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, Map.of(sourcePos, sourceState)),
                            BlockPos.ZERO,
                            false),
                    "could not prepare rotated-stop capture journal");
            CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
            helper.assertTrue(level.removeBlock(framePos, false), "could not extract Frame for rotated stop");
            level.removeBlock(sourcePos, false);
            helper.assertTrue(level.getBlockState(sourcePos).isAir(), "source lever survived capture");
            assertLampsLit(helper, child, lampLocals, "while captured");

            FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, targetFacing);
            Quaterniond targetRotation = targetOrientation.quaternion(new Quaterniond());
            AssemblyPose targetPose = new AssemblyPose(
                    framePos.getX() + .5,
                    framePos.getY() + .5,
                    framePos.getZ() + .5,
                    targetRotation.x,
                    targetRotation.y,
                    targetRotation.z,
                    targetRotation.w);
            helper.assertTrue(manager.prepareContraptionPlacement(
                            level,
                            Map.of(assemblyId, Set.of(framePos)),
                            Map.of(assemblyId, framePos),
                            Map.of(assemblyId, targetPose)),
                    "could not prepare " + targetFacing + " docking journal");

            BlockState targetFrame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, targetFacing)
                    .setValue(MechanismFrameBlock.EMPTY, false);
            helper.assertTrue(level.setBlock(framePos, targetFrame, Block.UPDATE_ALL),
                    "could not restore rotated Frame");
            BlockPos targetSourcePos = framePos.relative(targetFacing);
            placePoweredWallLeverLikePlayer(helper, player, framePos, targetFacing);
            helper.assertTrue(level.getBlockState(targetSourcePos).is(Blocks.LEVER)
                            && level.getBlockState(targetSourcePos).getValue(BlockStateProperties.POWERED),
                    "rotated powered wall lever did not survive restore");

            helper.assertTrue(manager.finalizeContraptionPlacement(level, Set.of(assemblyId)),
                    "could not finalize " + targetFacing + " Create stop");
            helper.assertTrue(manager.pendingContraptionMove(assemblyId).isEmpty(),
                    "Create journal survived successful stop");
            helper.assertTrue(assembly.orientation().front() == targetFacing,
                    "assembly did not commit target facing " + targetFacing);

            helper.runAfterDelay(6, () -> {
                assertLampsLit(helper, child, lampLocals, "after rotated stop at " + targetFacing);
                helper.succeed();
            });
        });
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
        helper.assertTrue(result.consumesAction(), "vanilla wall-lever placement failed on " + physicalFace);

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState placed = level.getBlockState(leverPos);
        helper.assertTrue(placed.is(Blocks.LEVER), "wall lever did not appear on " + physicalFace);
        helper.assertTrue(level.setBlock(
                        leverPos,
                        placed.setValue(BlockStateProperties.POWERED, true),
                        Block.UPDATE_ALL),
                "could not switch wall lever on " + physicalFace);
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);
        BlockState powered = level.getBlockState(leverPos);
        helper.assertTrue(powered.is(Blocks.LEVER) && powered.getValue(BlockStateProperties.POWERED),
                "wall lever did not remain powered on " + physicalFace);
        return powered;
    }

    private static void seedLitLamp(ServerSubLevel child, BlockPos local) {
        BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, lit, Block.UPDATE_ALL),
                "could not seed lit mini lamp at logical position " + local);
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(local).is(Blocks.REDSTONE_LAMP),
                "seeded mini lamp missing at logical position " + local);
    }

    private static void synchronizeFixtureFrame(
            ServerLevel level,
            ServerSubLevel child,
            BlockPos framePos,
            int occupiedMask,
            String description) {
        // Direct fixture writes do not always trigger Sable's plot-level bounds rebuild before the
        // lazy retirement sweep. Gameplay placement does, so explicitly establish the same non-empty
        // plot bounds before advancing any GameTest ticks.
        child.getPlot().updateBoundingBox();
        check(!MechanismSubLevelService.isPhysicallyEmpty(child),
                description + " left the managed SubLevel physically empty");

        BlockState state = level.getBlockState(framePos);
        check(state.is(ModRegistries.MECHANISM_FRAME.get()), description + " lost its Frame");
        if (state.getValue(MechanismFrameBlock.EMPTY)) {
            check(level.setBlock(framePos, state.setValue(MechanismFrameBlock.EMPTY, false), Block.UPDATE_ALL),
                    "could not synchronize EMPTY for " + description);
        }
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                description + " lost its Frame block entity");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
        frame.setOccupiedMask(occupiedMask);
        check(frame.getOccupiedMask() == occupiedMask,
                description + " occupiedMask mismatch after fixture synchronization");
        check(!level.getBlockState(framePos).getValue(MechanismFrameBlock.EMPTY),
                description + " remained EMPTY after fixture synchronization");
    }

    private static BlockPos physicalBoundaryCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static void assertLampsLit(
            GameTestHelper helper,
            ServerSubLevel child,
            List<BlockPos> lampLocals,
            String phase) {
        helper.assertFalse(child.isRemoved(), "managed mini world was removed " + phase);
        for (BlockPos local : lampLocals) {
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            helper.assertTrue(state.is(Blocks.REDSTONE_LAMP),
                    "mini lamp disappeared " + phase + " at logical position " + local);
            helper.assertTrue(state.getValue(BlockStateProperties.LIT),
                    "mini lamp went dark " + phase + " at logical position " + local);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
