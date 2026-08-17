package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for separating a docked Frame's physical pose from its logical mini mapping. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DockedSubLevelOrientationGameTests {
    private DockedSubLevelOrientationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void upsideDownLogicalMappingKeepsDockedSubLevelUpright(GameTestHelper helper) {
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
        FrameOrientation logicalTarget = new FrameOrientation(Direction.DOWN, Direction.SOUTH);
        AssemblyPose logicalPose = poseAt(target, logicalTarget);
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(target)),
                        Map.of(id, target),
                        Map.of(id, logicalPose)),
                "could not journal upside-down logical target");

        // A placed Mechanism Frame cannot pitch/roll. Its transformed horizontal facing is SOUTH,
        // while the full DOWN/SOUTH basis remains only in the assembly's logical mapping.
        placeFrame(level, target, Direction.SOUTH);
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "could not commit target Frame");

        MechanismAssembly docked = manager.getAssembly(id).orElseThrow();
        MechanismFrameBlockEntity frame = requireFrame(level, target);
        FrameOrientation physical = frame.getPhysicalFrameOrientation();

        check(docked.orientation().equals(logicalTarget), "logical assembly orientation was flattened");
        check(frame.getFrameOrientation().equals(logicalTarget), "Frame lost its logical region mapping");
        check(physical.equals(new FrameOrientation(Direction.UP, Direction.SOUTH)),
                "placed Frame did not expose its upright physical orientation");
        check(FrameOrientation.fromQuaternion(docked.poseTarget().orientation(new Quaterniond()))
                        .orElseThrow().equals(logicalTarget),
                "semantic Create pose no longer retains the logical snapped orientation");

        AssemblyPose worldPose = MechanismAssemblyHost.worldPose(level, docked);
        check(worldPose != null, "docked assembly has no physical sublevel pose");
        FrameOrientation subLevelOrientation = FrameOrientation.fromQuaternion(
                worldPose.orientation(new Quaterniond())).orElseThrow();
        check(subLevelOrientation.equals(physical),
                "docked sublevel stayed pitched/rolled instead of matching the placed Frame");
        check(subLevelOrientation.up() == Direction.UP, "docked sublevel is still upside down");
        helper.succeed();
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
