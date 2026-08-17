package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
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

/** Regression coverage for Create legitimately skipping a Frame at an obstructed target. */
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
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, middle, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        check(level.setBlock(miniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed bridge Frame mini content");

        check(manager.prepareContraptionMoves(level, Map.of(originalId, sources), BlockPos.ZERO, false),
                "could not journal Create capture");
        sources.forEach(pos -> level.removeBlock(pos, false));

        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, Direction.NORTH);
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

        placeFrame(level, targetBySource.get(left));
        placeFrame(level, targetBySource.get(right));
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

        helper.runAfterDelay(3, () -> {
            AABB finalPlacementArea = new AABB(
                    blockedTarget.getX() - 1.0,
                    blockedTarget.getY() - 1.0,
                    blockedTarget.getZ() - 1.0,
                    blockedTarget.getX() + 2.0,
                    blockedTarget.getY() + 2.0,
                    blockedTarget.getZ() + 2.0);
            boolean cobblestoneAtFinalPose = !level.getEntitiesOfClass(
                            ItemEntity.class,
                            finalPlacementArea,
                            entity -> entity.getItem().is(Items.COBBLESTONE))
                    .isEmpty();
            check(cobblestoneAtFinalPose,
                    "skipped Frame mini drops were not projected around Create's final placement pose");
            helper.succeed();
        });
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
        check(level.setBlock(
                        pos,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                        Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
