package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-world regression coverage for Create frame orientation and transaction invariants. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MechanismAssemblyRotationGameTests {
    private MechanismAssemblyRotationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void incompatibleAdjacentFramesDoNotMerge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos north = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos east = helper.absolutePos(new BlockPos(3, 2, 2));
        placeFrame(level, north, Direction.NORTH);
        placeFrame(level, east, Direction.EAST);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly left = requireAssembly(manager, north);
        MechanismAssembly right = requireAssembly(manager, east);
        check(!left.id().equals(right.id()), "differently oriented adjacent Frames merged");
        check(left.orientation().front() == Direction.NORTH, "north assembly orientation changed");
        check(right.orientation().front() == Direction.EAST, "east assembly orientation changed");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void bridgeFrameDoesNotJoinIncompatibleAssemblies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos leftPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos middle = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos rightPos = helper.absolutePos(new BlockPos(4, 2, 2));
        placeFrame(level, leftPos, Direction.NORTH);
        placeFrame(level, rightPos, Direction.EAST);
        UUID rightId = requireAssembly(MechanismAssemblyManager.get(level), rightPos).id();
        placeFrame(level, middle, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        UUID leftId = requireAssembly(manager, leftPos).id();
        check(leftId.equals(requireAssembly(manager, middle).id()), "bridge Frame did not join compatible assembly");
        check(!leftId.equals(rightId), "bridge Frame merged incompatible assemblies");
        check(rightId.equals(requireAssembly(manager, rightPos).id()), "incompatible assembly was reinterpreted");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void irregularMultiFrameRotatesThroughAllHorizontalOrientations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        Set<BlockPos> frames = Set.of(start, start.east(), start.south(), start.east().south());
        frames.forEach(pos -> placeFrame(level, pos, Direction.NORTH));
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, start);
        check(assembly.frames().equals(frames), "irregular assembly did not form as one component");

        List<Direction> fronts = List.of(Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH);
        for (int step = 0; step < fronts.size(); step++) {
            Direction front = fronts.get(step);
            FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, front);
            BlockPos targetOrigin = helper.absolutePos(new BlockPos(8 + step * 5, 2, 6));
            assembly = manager.getAssembly(assembly.id()).orElseThrow();
            Map<BlockPos, BlockPos> logicalBySource = logicalOffsets(assembly);
            Set<BlockPos> targetFrames = new LinkedHashSet<>();
            for (BlockPos logical : logicalBySource.values()) {
                targetFrames.add(targetOrigin.offset(targetOrientation.toPhysical(logical)));
            }
            rotateViaJournal(level, manager, assembly, targetOrigin, targetOrientation, targetFrames);
            assembly = manager.getAssembly(assembly.id()).orElseThrow();
            check(assembly.origin().equals(targetOrigin), "rotated origin mismatch at " + front);
            check(assembly.orientation().equals(targetOrientation), "rotated orientation mismatch at " + front);
            check(assembly.frames().equals(targetFrames), "rotated frame layout mismatch at " + front);
            for (BlockPos target : targetFrames) {
                MechanismFrameBlockEntity frame = requireFrameEntity(level, target);
                check(assembly.id().equals(frame.getAssemblyId()), "Frame BE assembly mapping mismatch");
                check(targetOrientation.equals(frame.getFrameOrientation()), "Frame BE orientation mismatch");
                check(assembly.logicalFrameOffset(target).equals(frame.getLogicalFrameOffset()), "Frame BE logical offset mismatch");
                check(level.getBlockState(target).getValue(MechanismFrameBlock.EMPTY), "empty Frame became occupied without mini content");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 150)
    public static void partialCommitFailureRollsBackMappingsAndKeepsJournal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        Set<BlockPos> sourceFrames = Set.of(start, start.east(), start.south());
        sourceFrames.forEach(pos -> placeFrame(level, pos, Direction.NORTH));
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, start);
        UUID id = assembly.id();
        BlockPos sourceOrigin = assembly.origin();
        FrameOrientation sourceOrientation = assembly.orientation();
        Map<BlockPos, UUID> sourceMappings = sourceFrames.stream().collect(java.util.stream.Collectors.toMap(
                pos -> pos, pos -> requireFrameEntity(level, pos).getAssemblyId()));

        check(manager.prepareContraptionMoves(level, Map.of(id, sourceFrames), BlockPos.ZERO, false), "could not journal capture");
        sourceFrames.forEach(pos -> level.removeBlock(pos, false));
        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, Direction.EAST);
        BlockPos targetOrigin = helper.absolutePos(new BlockPos(10, 2, 4));
        Set<BlockPos> targets = targetFrames(assembly, targetOrigin, targetOrientation);
        check(manager.prepareContraptionPlacement(level, Map.of(id, targets), Map.of(id, targetOrigin),
                Map.of(id, poseAt(targetOrigin, targetOrientation))), "could not journal target placement");
        targets.forEach(pos -> placeFrame(level, pos, targetOrientation.front()));
        Map<BlockPos, FrameSnapshot> targetBefore = snapshotFrames(level, targets);

        try (AutoCloseable ignored = MechanismAssemblyManager.installContraptionCommitProbe((assemblyId, frame, ordinal) -> {
            if (ordinal == 2) throw new IllegalStateException("intentional mid-commit GameTest fault");
        })) {
            check(!manager.finalizeContraptionPlacement(level, List.of(id)), "faulted transaction unexpectedly committed");
        } catch (Exception exception) {
            throw new AssertionError("could not install rollback probe", exception);
        }

        MechanismAssembly rolledBack = manager.getAssembly(id).orElseThrow();
        check(rolledBack.origin().equals(sourceOrigin), "assembly origin was not rolled back");
        check(rolledBack.frames().equals(sourceFrames), "assembly frame set was not rolled back");
        check(rolledBack.orientation().equals(sourceOrientation), "assembly orientation was not rolled back");
        check(manager.pendingContraptionMove(id).isPresent(), "recovery journal was dropped after failed commit");
        for (BlockPos source : sourceFrames) {
            check(manager.getAssemblyAt(source).map(MechanismAssembly::id).orElse(null).equals(id), "frameIndex source mapping was not restored");
            check(id.equals(sourceMappings.get(source)), "source mapping snapshot corrupted");
        }
        for (BlockPos target : targets) {
            check(targetBefore.get(target).equals(FrameSnapshot.capture(level, target)), "target Frame state/BE changed after rollback");
        }
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void abortedCaptureDropsJournalWithoutChangingAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        placeFrame(level, start, Direction.WEST);
        placeFrame(level, start.south(), Direction.WEST);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, start);
        UUID id = assembly.id();
        AssemblyPose pose = assembly.poseTarget();
        Set<BlockPos> frames = Set.copyOf(assembly.frames());
        check(manager.prepareContraptionMoves(level, Map.of(id, frames), BlockPos.ZERO, false), "capture journal was rejected");
        check(manager.pendingContraptionMove(id).isPresent(), "capture journal missing");
        manager.tick(level);
        check(manager.pendingContraptionMove(id).isEmpty(), "aborted capture journal was not cleared");
        MechanismAssembly after = manager.getAssembly(id).orElseThrow();
        check(after.frames().equals(frames), "aborted capture changed frames");
        check(after.poseTarget().approximatelyEquals(pose, 1.0E-10), "aborted capture changed pose");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void rotatedAssemblySplitPreservesOrientation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos left = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos middle = left.east();
        BlockPos right = middle.east();
        placeFrame(level, left, Direction.WEST);
        placeFrame(level, middle, Direction.WEST);
        placeFrame(level, right, Direction.WEST);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        UUID original = requireAssembly(manager, left).id();
        level.removeBlock(middle, false);
        MechanismAssembly leftAssembly = requireAssembly(manager, left);
        MechanismAssembly rightAssembly = requireAssembly(manager, right);
        check(!leftAssembly.id().equals(rightAssembly.id()), "split did not create two assemblies");
        check(leftAssembly.orientation().front() == Direction.WEST, "left split lost rotated orientation");
        check(rightAssembly.orientation().front() == Direction.WEST, "right split lost rotated orientation");
        check(leftAssembly.id().equals(original) || rightAssembly.id().equals(original), "original assembly id did not survive split");
        helper.succeed();
    }

    private static void rotateViaJournal(ServerLevel level, MechanismAssemblyManager manager, MechanismAssembly assembly,
                                         BlockPos targetOrigin, FrameOrientation targetOrientation, Set<BlockPos> targets) {
        UUID id = assembly.id();
        Set<BlockPos> sources = Set.copyOf(assembly.frames());
        check(manager.prepareContraptionMoves(level, Map.of(id, sources), BlockPos.ZERO, false), "capture preflight failed");
        sources.forEach(pos -> level.removeBlock(pos, false));
        check(manager.prepareContraptionPlacement(level, Map.of(id, targets), Map.of(id, targetOrigin),
                Map.of(id, poseAt(targetOrigin, targetOrientation))), "placement preflight failed");
        targets.forEach(pos -> placeFrame(level, pos, targetOrientation.front()));
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "placement commit failed");
        check(manager.pendingContraptionMove(id).isEmpty(), "journal survived successful commit");
    }

    private static Map<BlockPos, BlockPos> logicalOffsets(MechanismAssembly assembly) {
        return assembly.frames().stream().collect(java.util.stream.Collectors.toMap(pos -> pos, assembly::logicalFrameOffset));
    }

    private static Set<BlockPos> targetFrames(MechanismAssembly assembly, BlockPos targetOrigin, FrameOrientation targetOrientation) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (BlockPos source : assembly.frames()) {
            result.add(targetOrigin.offset(targetOrientation.toPhysical(assembly.logicalFrameOffset(source))));
        }
        return result;
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond q = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5,
                q.x, q.y, q.z, q.w);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static MechanismAssembly requireAssembly(MechanismAssemblyManager manager, BlockPos pos) {
        return manager.getAssemblyAt(pos).orElseThrow(() -> new AssertionError("missing assembly at " + pos));
    }

    private static MechanismFrameBlockEntity requireFrameEntity(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MechanismFrameBlockEntity frame) return frame;
        throw new AssertionError("missing Frame BlockEntity at " + pos);
    }

    private static Map<BlockPos, FrameSnapshot> snapshotFrames(ServerLevel level, Collection<BlockPos> frames) {
        return frames.stream().collect(java.util.stream.Collectors.toMap(pos -> pos, pos -> FrameSnapshot.capture(level, pos)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record FrameSnapshot(BlockState state, UUID assemblyId, FrameOrientation orientation,
                                 BlockPos logicalOffset, int occupiedMask) {
        private static FrameSnapshot capture(ServerLevel level, BlockPos pos) {
            MechanismFrameBlockEntity frame = requireFrameEntity(level, pos);
            return new FrameSnapshot(level.getBlockState(pos), frame.getAssemblyId(), frame.getFrameOrientation(),
                    frame.getLogicalFrameOffset(), frame.getOccupiedMask());
        }
    }
}
