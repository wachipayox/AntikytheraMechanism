package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Follow-up regressions for fluid placement and Create's Frame-absent transaction windows. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameBoundaryFollowupGameTests {
    private FrameBoundaryFollowupGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void waterBucketCannotReplaceFrameOrMutateOwnership(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, framePos, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        UUID assemblyId = manager.getAssemblyAt(framePos).orElseThrow().id();

        // Exercise Minecraft's bucket placement route instead of an unconditional Level#setBlock
        // overwrite. The latter is an administrative low-level mutation and does not model flowing
        // water or a player's bucket attempting to occupy the Frame cell.
        BucketItem waterBucket = (BucketItem) Items.WATER_BUCKET;
        boolean emptied = waterBucket.emptyContents(
                null,
                level,
                framePos,
                null,
                new ItemStack(Items.WATER_BUCKET));
        check(!emptied, "water bucket unexpectedly replaced the Frame");
        check(level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame disappeared after rejected bucket placement");
        check(level.getFluidState(framePos).isEmpty(),
                "rejected bucket placement left fluid inside the Frame cell");
        check(manager.getAssemblyAt(framePos).map(MechanismAssembly::id).filter(assemblyId::equals).isPresent(),
                "rejected bucket placement mutated Frame assembly ownership");
        check(!manager.isFrameEvacuated(framePos),
                "rejected bucket placement incorrectly entered the evacuation lifecycle");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void createJournalSuppliesSupportWhileSourceAndTargetFramesAreAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, sourceFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(sourceFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Fill all eight logical cells so every physical face has a complete four-cell sturdy mini
        // boundary. The assertions below can therefore exercise all six support directions.
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, sourceFrame, x, y, z);
                    BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
                    check(MiniWorldEnvironment.withVirtualReads(() ->
                                    level.setBlock(miniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                            "could not fill sturdy mini support cell " + miniLocal);
                }
            }
        }
        manager.refreshFrame(level, sourceFrame);

        Map<BlockPos, BlockState> carriedBoundary = new HashMap<>();
        for (Direction direction : Direction.values()) {
            carriedBoundary.put(sourceFrame.relative(direction), Blocks.STONE.defaultBlockState());
        }
        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(assembly.id(), Set.of(sourceFrame)),
                        Map.of(assembly.id(), carriedBoundary),
                        BlockPos.ZERO,
                        false),
                "could not prepare Create capture journal");

        // Source half of the transaction: Create has removed the Frame, but a carried attachment can
        // still receive a survival update before its own extraction finishes.
        check(level.removeBlock(sourceFrame, false), "could not mirror Create source Frame extraction");
        check(level.getBlockState(sourceFrame).isAir(), "source Frame did not become AIR");
        for (Direction direction : Direction.values()) {
            check(level.getBlockState(sourceFrame).isFaceSturdy(
                            level, sourceFrame, direction, SupportType.FULL),
                    "source AIR lost journaled mini support on " + direction);
        }

        // Destination half: the attachment may be restored before the Frame itself. Use a quarter
        // turn so physical faces are forced through the immutable logical basis rather than matching
        // the source axes by accident.
        BlockPos targetFrame = sourceFrame.offset(4, 0, 0);
        FrameOrientation targetOrientation = FrameOrientation.IDENTITY.rotate(Direction.Axis.Y, 1);
        Quaterniond targetRotation = targetOrientation.quaternion(new Quaterniond());
        AssemblyPose targetPose = new AssemblyPose(
                targetFrame.getX() + .5,
                targetFrame.getY() + .5,
                targetFrame.getZ() + .5,
                targetRotation.x,
                targetRotation.y,
                targetRotation.z,
                targetRotation.w);
        Map<UUID, Set<BlockPos>> targetFrames = Map.of(assembly.id(), Set.of(targetFrame));
        Map<UUID, BlockPos> targetOrigins = Map.of(assembly.id(), targetFrame);
        Map<UUID, AssemblyPose> targetPoses = Map.of(assembly.id(), targetPose);
        check(manager.prepareContraptionPlacement(level, targetFrames, targetOrigins, targetPoses),
                "could not prepare Create destination journal");

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targetFrames, targetOrigins, targetPoses);
        try {
            check(level.getBlockState(targetFrame).isAir(),
                    "test requires the destination support position to still be AIR");
            for (Direction direction : Direction.values()) {
                check(level.getBlockState(targetFrame).isFaceSturdy(
                                level, targetFrame, direction, SupportType.FULL),
                        "target AIR lost journaled mini support on rotated face " + direction);
            }
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }
        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
