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

/** Regression coverage for macro-powered lamps across Create capture, motion and rotated docking. */
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

        // Gameplay placement calls refreshFrame after each successful mini placement. Direct GameTest
        // plot writes must establish the same occupiedMask/EMPTY state before exercising the boundary.
        synchronizeSeededMiniContent(level, manager, framePos, 4, "front lamp fixture");

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = poweredWallLever(physicalFace);
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

        List<BlockPos> lamps = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos local = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
                    BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                    check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(
                                    global,
                                    Blocks.REDSTONE_LAMP.defaultBlockState(),
                                    Block.UPDATE_ALL)),
                            "could not fill mini lamp cube at " + local);
                    lamps.add(global);
                }
            }
        }

        // Match the state produced by the normal mini-placement route before adding macro power.
        synchronizeSeededMiniContent(level, manager, framePos, 8, "filled lamp cube fixture");

        BlockPos sourceLeverPos = framePos.north();
        BlockState sourceLever = poweredWallLever(Direction.NORTH);
        check(level.setBlock(sourceLeverPos, sourceLever, Block.UPDATE_ALL), "could not place source lever");
        MiniWorldEnvironment.parentBlockChanged(level, sourceLeverPos);
        assertAllPoweredAndLit(level, lamps, "before rotated capture", Direction.NORTH);

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assemblyId, Set.of(framePos)),
                        Map.of(assemblyId, Map.of(sourceLeverPos, sourceLever)),
                        BlockPos.ZERO,
                        false),
                "could not prepare rotated-stop capture journal");
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(assemblyId));
        check(level.removeBlock(framePos, false), "could not extract Frame for rotated stop");
        check(level.removeBlock(sourceLeverPos, false), "could not extract lever for rotated stop");

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
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(assemblyId, Set.of(framePos)),
                        Map.of(assemblyId, framePos),
                        Map.of(assemblyId, targetPose)),
                "could not prepare " + targetFacing + " docking journal");

        BlockState targetFrame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, targetFacing)
                .setValue(MechanismFrameBlock.EMPTY, false);
        check(level.setBlock(framePos, targetFrame, Block.UPDATE_ALL), "could not restore rotated Frame");
        BlockPos targetLeverPos = framePos.relative(targetFacing);
        BlockState targetLever = poweredWallLever(targetFacing);
        check(level.setBlock(targetLeverPos, targetLever, Block.UPDATE_ALL), "could not restore rotated lever");

        check(manager.finalizeContraptionPlacement(level, Set.of(assemblyId)),
                "could not finalize " + targetFacing + " Create stop");
        check(manager.pendingContraptionMove(assemblyId).isEmpty(), "Create journal survived successful stop");
        check(level.getBlockState(targetLeverPos).getValue(BlockStateProperties.POWERED),
                "restored lever is no longer powered");

        helper.runAfterDelay(6, () -> {
            assertAllPoweredAndLit(level, lamps, "after rotated stop at " + targetFacing, targetFacing);
            helper.succeed();
        });
    }

    private static void synchronizeSeededMiniContent(
            ServerLevel level,
            MechanismAssemblyManager manager,
            BlockPos framePos,
            int expectedOccupiedCells,
            String fixtureName) {
        manager.refreshFrame(level, framePos);
        BlockState refreshed = level.getBlockState(framePos);
        check(refreshed.is(ModRegistries.MECHANISM_FRAME.get()), fixtureName + " lost its Frame");
        check(!refreshed.getValue(MechanismFrameBlock.EMPTY), fixtureName + " left Frame marked EMPTY");
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                fixtureName + " lost its Frame block entity");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
        check(Integer.bitCount(frame.getOccupiedMask()) == expectedOccupiedCells,
                fixtureName + " occupiedMask mismatch: expected " + expectedOccupiedCells
                        + ", got " + Integer.bitCount(frame.getOccupiedMask()));
    }

    private static BlockState poweredWallLever(Direction physicalFace) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
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
