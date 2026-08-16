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
        List<BlockPos> lampLocals = new ArrayList<>();
        int occupiedMask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos physicalCell = physicalBoundaryCell(physicalFace, a, b);
                BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(
                        assembly, framePos, physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
                seedMiniBlock(child, local, Blocks.REDSTONE_LAMP.defaultBlockState(), "front mini lamp");
                lampLocals.add(local);
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(
                        physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
            }
        }
        synchronizeFixtureFrame(level, framePos, occupiedMask, "front lamp fixture");

        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = placePoweredMacroLeverFixture(level, framePos, physicalFace);

        // Real gameplay advances ticks after a source changes before Create captures the structure.
        helper.runAfterDelay(2, () -> {
            primePoweredLampState(level, child, lampLocals, "before capture", frameFacing);

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
            assertAllPoweredAndLit(level, child, lampLocals, "after physical capture", frameFacing);

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
            assertAllPoweredAndLit(level, child, lampLocals, "during in-flight pose", frameFacing);
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
                    seedMiniBlock(child, local, Blocks.REDSTONE_LAMP.defaultBlockState(), "filled mini lamp cube");
                    lampLocals.add(local);
                }
            }
        }
        synchronizeFixtureFrame(level, framePos, 0xFF, "filled lamp cube fixture");

        BlockPos sourceLeverPos = framePos.north();
        BlockState sourceLever = placePoweredMacroLeverFixture(level, framePos, Direction.NORTH);

        helper.runAfterDelay(2, () -> {
            primePoweredLampState(level, child, lampLocals, "before rotated capture", Direction.NORTH);

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
                assertAllPoweredAndLit(level, child, lampLocals, "after rotated stop at " + targetFacing, targetFacing);
                helper.succeed();
            });
        });
    }

    private static void seedMiniBlock(
            ServerSubLevel child,
            BlockPos local,
            BlockState state,
            String description) {
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, state, Block.UPDATE_ALL),
                "could not seed " + description + " at logical mini position " + local);
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(local).is(state.getBlock()),
                description + " was not stored in managed SubLevel at " + local);
    }

    private static void synchronizeFixtureFrame(
            ServerLevel level,
            BlockPos framePos,
            int occupiedMask,
            String description) {
        BlockState state = level.getBlockState(framePos);
        check(state.is(ModRegistries.MECHANISM_FRAME.get()), description + " lost its Frame");
        boolean empty = occupiedMask == 0;
        if (state.getValue(MechanismFrameBlock.EMPTY) != empty) {
            check(level.setBlock(framePos, state.setValue(MechanismFrameBlock.EMPTY, empty), Block.UPDATE_ALL),
                    "could not synchronize EMPTY for " + description);
        }
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                description + " lost its Frame block entity");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
        frame.setOccupiedMask(occupiedMask);
        check(frame.getOccupiedMask() == occupiedMask,
                description + " occupiedMask mismatch after fixture synchronization");
        check(level.getBlockState(framePos).getValue(MechanismFrameBlock.EMPTY) == empty,
                description + " EMPTY mismatch after fixture synchronization");
    }

    private static BlockState placePoweredMacroLeverFixture(
            ServerLevel level,
            BlockPos framePos,
            Direction physicalFace) {
        BlockPos leverPos = framePos.relative(physicalFace);
        BlockState lever = poweredWallLever(physicalFace);
        check(level.setBlock(leverPos, lever, Block.UPDATE_ALL),
                "could not establish reachable powered macro lever on " + physicalFace);
        check(level.getBlockState(leverPos).is(Blocks.LEVER)
                        && level.getBlockState(leverPos).getValue(BlockStateProperties.POWERED),
                "powered macro lever fixture did not survive on " + physicalFace);
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);
        return level.getBlockState(leverPos);
    }

    private static void primePoweredLampState(
            ServerLevel level,
            ServerSubLevel child,
            List<BlockPos> lampLocals,
            String phase,
            Direction frameFacing) {
        for (BlockPos local : lampLocals) {
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            boolean powered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(global));
            check(powered, frameFacing + " Frame mini lamp lacks macro power " + phase + " at " + local);
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            check(state.is(Blocks.REDSTONE_LAMP), "mini lamp missing " + phase + " at " + local);
            if (!state.getValue(BlockStateProperties.LIT)) {
                check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                                local,
                                state.setValue(BlockStateProperties.LIT, true),
                                Block.UPDATE_ALL),
                        "could not establish already-powered lamp state " + phase + " at " + local);
            }
            check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(local)
                            .getValue(BlockStateProperties.LIT),
                    frameFacing + " Frame mini lamp is not lit " + phase + " at " + local);
        }
    }

    private static BlockPos physicalBoundaryCell(Direction face, int a, int b) {
        return switch (face.getAxis()) {
            case X -> new BlockPos(face == Direction.WEST ? 0 : 1, a, b);
            case Y -> new BlockPos(a, face == Direction.DOWN ? 0 : 1, b);
            case Z -> new BlockPos(a, b, face == Direction.NORTH ? 0 : 1);
        };
    }

    private static BlockState poweredWallLever(Direction physicalFace) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
    }

    private static void assertAllPoweredAndLit(
            ServerLevel level,
            ServerSubLevel child,
            List<BlockPos> lampLocals,
            String phase,
            Direction frameFacing) {
        for (BlockPos local : lampLocals) {
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            boolean powered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(global));
            check(powered, frameFacing + " Frame mini lamp lost macro power " + phase + " at " + local);
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(local);
            check(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + local);
            check(state.getValue(BlockStateProperties.LIT),
                    frameFacing + " Frame mini lamp went dark " + phase + " at " + local);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
