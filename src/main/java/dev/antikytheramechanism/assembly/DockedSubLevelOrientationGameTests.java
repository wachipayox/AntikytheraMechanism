package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameFaceSupport;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.OrientedRedstoneBoundary;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for canonical upright yaw after Create returns a Frame to the world. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DockedSubLevelOrientationGameTests {
    private DockedSubLevelOrientationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void flippedSingleFrameCanonicalizesBackToUprightYaw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 2, 2));
        placeFrame(level, source, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(source).orElseThrow();
        UUID id = assembly.id();

        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(source)), BlockPos.ZERO, false),
                "could not journal source Frame");
        level.removeBlock(source, false);

        BlockPos target = helper.absolutePos(new BlockPos(7, 2, 2));
        AssemblyPose flippedPose = flippedPoseAt(target);
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(target)),
                        Map.of(id, target),
                        Map.of(id, flippedPose)),
                "could not journal 180-degree Create target");

        // A 180-degree X rotation turns NORTH into SOUTH, but the placed Frame itself is still Y-up.
        placeFrame(level, target, Direction.SOUTH);
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "could not commit target Frame");

        MechanismAssembly docked = manager.getAssembly(id).orElseThrow();
        MechanismFrameBlockEntity frame = requireFrame(level, target);
        FrameOrientation expected = new FrameOrientation(Direction.SOUTH);

        check(docked.orientation().equals(expected), "static assembly retained non-yaw logical orientation");
        check(frame.getFrameOrientation().equals(expected), "Frame BE retained non-yaw logical orientation");
        check(frame.getPhysicalFrameOrientation().equals(expected), "Frame BE disagrees with placed BlockState yaw");
        check(docked.poseTarget().approximatelyEquals(poseAt(target, expected), 1.0E-10),
                "static semantic pose retained hidden pitch/roll after Create placement");

        AssemblyPose worldPose = MechanismAssemblyHost.worldPose(level, docked);
        check(worldPose != null, "docked assembly has no physical sublevel pose");
        check(worldPose.approximatelyEquals(poseAt(target, expected), 1.0E-10),
                "docked sublevel pose disagrees with canonical static yaw");
        check(docked.orientation().up() == Direction.UP, "static Frame orientation is not world-up");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void flippedSingleFrameRebindsSupportAndRedstoneToVisibleFaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 3, 2));
        placeFrame(level, source, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(source).orElseThrow();
        UUID id = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Make Y deliberately asymmetric. The visible bottom face is completely solid and contains one
        // redstone source; the visible top face is empty. A stale DOWN/SOUTH logical basis used to swap
        // these two physical boundaries after an upside-down Create disassembly.
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos local = MiniCoordinateMapper.frameToMini(assembly, source, x, 0, z);
                BlockState state = x == 0 && z == 0
                        ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                        : Blocks.SMOOTH_STONE.defaultBlockState();
                check(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, state, Block.UPDATE_ALL),
                        "could not populate asymmetric mini bottom face");
            }
        }
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, source);

        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(source)), BlockPos.ZERO, false),
                "could not journal source Frame");
        level.removeBlock(source, false);

        BlockPos target = helper.absolutePos(new BlockPos(8, 3, 2));
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(target)),
                        Map.of(id, target),
                        Map.of(id, flippedPoseAt(target))),
                "could not journal flipped Create target");
        placeFrame(level, target, Direction.SOUTH);
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "could not commit flipped Frame");

        MechanismAssembly docked = manager.getAssembly(id).orElseThrow();
        check(docked.orientation().equals(new FrameOrientation(Direction.SOUTH)),
                "flipped Frame did not canonicalize to SOUTH yaw");
        check(docked.poseTarget().approximatelyEquals(poseAt(target, docked.orientation()), 1.0E-10),
                "flipped Frame kept a hidden pitch/roll pose");

        // Full macro receiver blocks make all four 1/2x1/2 quadrants relevant to the redstone bridge.
        check(level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place lower macro receiver");
        check(level.setBlock(target.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place upper macro receiver");

        Boolean bottomSupport = FrameFaceSupport.query(level, target, Direction.DOWN, SupportType.FULL);
        Boolean topSupport = FrameFaceSupport.query(level, target, Direction.UP, SupportType.FULL);
        check(Boolean.TRUE.equals(bottomSupport),
                "visible solid mini bottom face stopped supporting the macro block after flipped placement");
        check(Boolean.FALSE.equals(topSupport),
                "empty visible mini top face retained stale support after flipped placement");

        // OrientedRedstoneBoundary receives the query direction from the macro receiver, hence UP
        // queries the Frame's outward DOWN face and DOWN queries its outward UP face.
        Integer bottomPower = OrientedRedstoneBoundary.output(level, target, Direction.UP, false);
        Integer topPower = OrientedRedstoneBoundary.output(level, target, Direction.DOWN, false);
        check(bottomPower != null && bottomPower > 0,
                "visible mini bottom redstone source stopped powering the lower macro face");
        check(topPower != null && topPower == 0,
                "empty visible mini top face retained stale redstone output after flipped placement");
        helper.succeed();
    }

    private static AssemblyPose flippedPoseAt(BlockPos origin) {
        Quaterniond q = new Quaterniond().rotateX(Math.PI);
        return new AssemblyPose(
                origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5,
                q.x, q.y, q.z, q.w);
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond q = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(
                origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5,
                q.x, q.y, q.z, q.w);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static MechanismFrameBlockEntity requireFrame(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MechanismFrameBlockEntity frame) return frame;
        throw new AssertionError("missing Frame BlockEntity at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
