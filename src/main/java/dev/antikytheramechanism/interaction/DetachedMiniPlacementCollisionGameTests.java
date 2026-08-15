package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/** Regression for Sable's scale-unaware BlockPlaceContext collision veto on grounded 0.5 bodies. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DetachedMiniPlacementCollisionGameTests {
    private DetachedMiniPlacementCollisionGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void groundedDetachedBodyAllowsFaceContactButRejectsTerrainPenetration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(4, 2, 4));
        check(level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place root terrain floor");

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        Pose3d pose = new Pose3d();
        pose.scale().set(
                MiniCoordinateMapper.SUBLEVEL_SCALE,
                MiniCoordinateMapper.SUBLEVEL_SCALE,
                MiniCoordinateMapper.SUBLEVEL_SCALE);
        ServerSubLevel body = (ServerSubLevel) container.allocateNewSubLevel(pose);
        body.getPlot().newEmptyChunk(body.getPlot().getCenterChunk());
        DetachedMiniPhysicsSubLevelService.markDetached(body);

        BlockPos target = body.getPlot().getCenterBlock();
        Vector3d localCenter = new Vector3d(
                target.getX() + .5,
                target.getY() + .5,
                target.getZ() + .5);
        body.logicalPose().rotationPoint().set(localCenter);
        // A scale-0.5 block has half-height 0.25. Put its lower face exactly on the floor's y+1 face.
        body.logicalPose().position().set(
                floor.getX() + .5,
                floor.getY() + 1.25,
                floor.getZ() + .5);
        body.updateBoundingBox();
        body.updateLastPose();

        check(ManagedPlacementCollisionPolicy.scaleAwareCollisionIsClear(level, target, body),
                "face-to-face contact with the floor was incorrectly treated as a placement collision");

        // Ten centimeters of actual world-space penetration must still be rejected. This proves the
        // fix is scale-aware collision, not a blanket 'ignore terrain around mini bodies' bypass.
        body.logicalPose().position().add(0.0, -0.10, 0.0);
        body.updateBoundingBox();
        check(!ManagedPlacementCollisionPolicy.scaleAwareCollisionIsClear(level, target, body),
                "real terrain penetration was incorrectly accepted for a detached mini body");
        helper.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
