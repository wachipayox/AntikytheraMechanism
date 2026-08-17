package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for partial/replacing Create Frame placement outcomes. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreatePartialPlacementGameTests {
    private CreatePartialPlacementGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void skippedBridgeFrameEvacuatesMiniContentsAndSplitsAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos left = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos middle = left.east();
        BlockPos right = middle.east();
        placeFrame(level, left);
        placeFrame(level, middle);
        placeFrame(level, right);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(middle).orElseThrow();
        UUID originalId = assembly.id();
        Set<BlockPos> sources = Set.copyOf(assembly.frames());
        check(sources.size() == 3, "three Frames did not form one assembly");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize mini world");
        BlockPos middleMiniLocal = MiniCoordinateMapper.frameToMini(assembly, middle, 0, 0, 0);
        BlockPos middleMiniGlobal = MechanismSubLevelService.toPlotPosition(child, middleMiniLocal);
        check(level.setBlock(middleMiniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed bridge Frame mini content");
        // Keep real payload on both surviving sides. The right component must therefore receive a
        // newly materialized managed child during the split, which is the lifecycle that used to
        // preserve Create's pitch/roll until the world was reloaded.
        BlockPos leftMiniGlobal = MechanismSubLevelService.toPlotPosition(
                child, MiniCoordinateMapper.frameToMini(assembly, left, 0, 0, 0));
        BlockPos rightMiniGlobal = MechanismSubLevelService.toPlotPosition(
                child, MiniCoordinateMapper.frameToMini(assembly, right, 0, 0, 0));
        check(level.setBlock(leftMiniGlobal, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed left survivor mini content");
        check(level.setBlock(rightMiniGlobal, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed right survivor mini content");

        check(manager.prepareContraptionMoves(level, Map.of(originalId, sources), BlockPos.ZERO, false),
                "could not journal Create capture");
        sources.forEach(pos -> level.removeBlock(pos, false));

        // 180 degrees around X leaves this X-axis Frame line in place while carrying a complete
        // upside-down logical orientation. Static Frame BlockStates remain physically Y-up.
        FrameOrientation targetOrientation = new FrameOrientation(Direction.DOWN, Direction.SOUTH);
        BlockPos targetOrigin = helper.absolutePos(new BlockPos(8, 3, 3));
        Map<BlockPos, BlockPos> targetBySource = new LinkedHashMap<>();
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (BlockPos source : sources) {
            BlockPos logical = assembly.logicalFrameOffset(source);
            BlockPos target = targetOrigin.offset(targetOrientation.toPhysical(logical));
            targetBySource.put(source, target);
            targets.add(target);
        }
        BlockPos blockedTarget = targetBySource.get(middle);
        check(level.setBlock(blockedTarget, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed indestructible obstruction");
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(originalId, targets),
                        Map.of(originalId, targetOrigin),
                        Map.of(originalId, poseAt(targetOrigin, targetOrientation))),
                "could not journal Create placement");

        placeFrame(level, targetBySource.get(left), Direction.SOUTH);
        placeFrame(level, targetBySource.get(right), Direction.SOUTH);
        CreatePlacementCommitService.CommitResult result =
                CreatePlacementCommitService.finalizePreparedPlacement(level, List.of(originalId));
        check(result.committed(), "partial Create placement did not commit");
        check(manager.pendingContraptionMove(originalId).isEmpty(), "Create journal survived partial commit");
        check(level.getBlockState(blockedTarget).is(Blocks.BEDROCK), "partial commit replaced the obstruction");

        MechanismAssembly leftAssembly = manager.getAssemblyAt(targetBySource.get(left)).orElseThrow();
        MechanismAssembly rightAssembly = manager.getAssemblyAt(targetBySource.get(right)).orElseThrow();
        check(!leftAssembly.id().equals(rightAssembly.id()), "missing bridge Frame did not split the graph");
        check(leftAssembly.frames().size() == 1 && rightAssembly.frames().size() == 1,
                "split components retained a phantom missing Frame");
        check(targetOrientation.equals(leftAssembly.orientation())
                        && targetOrientation.equals(rightAssembly.orientation()),
                "split flattened the logical Create orientation");

        // This is deliberately same-tick coverage. A reload used to repair the right child because
        // its Frame mapping was already correct on disk; the regression is that both live children
        // must become physically upright immediately after ownership is split.
        assertPlacedChildMatchesFrame(level, leftAssembly, targetBySource.get(left));
        assertPlacedChildMatchesFrame(level, rightAssembly, targetBySource.get(right));

        helper.runAfterDelay(3, () -> {
            check(hasItemNear(level, blockedTarget, Items.COBBLESTONE),
                    "skipped Frame mini drops were not projected around Create's final placement pose");
            helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void replacingDestinationFrameEvacuatesItsMiniRegionBeforeOwnershipTransfer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos movingSource = helper.absolutePos(new BlockPos(2, 3, 7));
        BlockPos destination = helper.absolutePos(new BlockPos(9, 3, 7));
        placeFrame(level, movingSource);
        placeFrame(level, destination);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly moving = manager.getAssemblyAt(movingSource).orElseThrow();
        MechanismAssembly displaced = manager.getAssemblyAt(destination).orElseThrow();
        check(!moving.id().equals(displaced.id()), "test Frames unexpectedly merged before movement");

        ServerSubLevel displacedChild = MechanismSubLevelService.ensureForContent(level, displaced);
        check(displacedChild != null && !displacedChild.isRemoved(), "could not materialize displaced mini world");
        BlockPos displacedMiniLocal = MiniCoordinateMapper.frameToMini(displaced, destination, 0, 0, 0);
        BlockPos displacedMiniGlobal = MechanismSubLevelService.toPlotPosition(displacedChild, displacedMiniLocal);
        check(level.setBlock(displacedMiniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed displaced Frame mini content");

        UUID movingId = moving.id();
        UUID displacedId = displaced.id();
        Set<BlockPos> movingFrames = Set.copyOf(moving.frames());
        check(manager.prepareContraptionMoves(level, Map.of(movingId, movingFrames), BlockPos.ZERO, false),
                "could not journal moving Frame capture");
        level.removeBlock(movingSource, false);

        FrameOrientation orientation = new FrameOrientation(Direction.UP, Direction.NORTH);
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();

        // This mirrors CreateContraptionLifecycle's temporary ownership masking. The old owner is
        // restored immediately after the placement journal so the post-placement commit can identify
        // which logical Frame Create replaced at the destination.
        check(displacedId.equals(frameIndex.remove(destination)), "displaced Frame index was not present");
        boolean prepared;
        try {
            prepared = manager.prepareContraptionPlacement(
                    level,
                    Map.of(movingId, Set.of(destination)),
                    Map.of(movingId, destination),
                    Map.of(movingId, poseAt(destination, orientation)));
        } finally {
            frameIndex.put(destination, displacedId);
        }
        check(prepared, "could not journal replacement placement while owner was temporarily masked");

        // Mirror Create's ordinary replacement behavior: destroy the old outer Frame, then place the
        // carried one. The active target journal intentionally suppresses ordinary Frame onRemove so
        // CreatePlacementCommitService owns the old mini evacuation exactly once.
        check(level.destroyBlock(destination, true), "could not destroy replaceable destination Frame");
        placeFrame(level, destination);

        CreatePlacementCommitService.CommitResult result =
                CreatePlacementCommitService.finalizePreparedPlacement(level, List.of(movingId));
        check(result.committed(), "replacement Create placement did not commit");
        check(manager.pendingContraptionMove(movingId).isEmpty(), "moving journal survived replacement commit");
        check(manager.getAssembly(displacedId).isEmpty(), "replaced one-Frame assembly survived after its Frame was destroyed");
        MechanismAssembly newOwner = manager.getAssemblyAt(destination).orElseThrow();
        check(newOwner.id().equals(movingId), "destination Frame did not transfer to the moving assembly");
        check(level.getBlockState(displacedMiniGlobal).isAir(), "replaced Frame mini content remained in its old plot");

        helper.runAfterDelay(3, () -> {
            check(hasItemNear(level, destination, Items.COBBLESTONE),
                    "replaced destination Frame did not evacuate its mini block drops near the destroyed Frame");
            helper.succeed();
        });
    }

    private static void assertPlacedChildMatchesFrame(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePosition) {
        check(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity,
                "split survivor lost its Frame BlockEntity at " + framePosition);
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePosition);
        check(assembly.id().equals(frame.getAssemblyId()),
                "split survivor Frame retained stale assembly ownership at " + framePosition);
        check(BlockPos.ZERO.equals(frame.getLogicalFrameOffset()),
                "single-Frame split survivor is not its assembly origin at " + framePosition);

        ServerSubLevel child = MechanismSubLevelService.get(level, assembly);
        check(child != null && !child.isRemoved(),
                "split survivor with mini payload did not retain a managed child at " + framePosition);
        FrameOrientation childOrientation = FrameOrientation.fromQuaternion(child.logicalPose().orientation())
                .orElseThrow(() -> new AssertionError("split survivor child pose is not orthogonal"));
        check(frame.getPhysicalFrameOrientation().equals(childOrientation),
                "split survivor child stayed rotated relative to its placed Frame at " + framePosition
                        + ": frame=" + frame.getPhysicalFrameOrientation() + ", child=" + childOrientation);
        check(childOrientation.up() == Direction.UP,
                "split survivor child was not forced physically upright at " + framePosition);
    }

    private static boolean hasItemNear(ServerLevel level, BlockPos center, net.minecraft.world.item.Item item) {
        AABB area = new AABB(
                center.getX() - 1.0,
                center.getY() - 1.0,
                center.getZ() - 1.0,
                center.getX() + 2.0,
                center.getY() + 2.0,
                center.getZ() + 2.0);
        return !level.getEntitiesOfClass(
                        ItemEntity.class,
                        area,
                        entity -> entity.getItem().is(item))
                .isEmpty();
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond quaternion = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(
                origin.getX() + .5,
                origin.getY() + .5,
                origin.getZ() + .5,
                quaternion.x,
                quaternion.y,
                quaternion.z,
                quaternion.w);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos) {
        placeFrame(level, pos, Direction.NORTH);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        check(level.setBlock(
                        pos,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing),
                        Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
