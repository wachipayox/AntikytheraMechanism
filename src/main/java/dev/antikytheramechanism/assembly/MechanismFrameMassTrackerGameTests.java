package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.ManagedFrameMassPolicy;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
import org.joml.Vector3dc;

/** Direct Sable mass regression coverage for non-colliding Mechanism Frames. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MechanismFrameMassTrackerGameTests {
    private MechanismFrameMassTrackerGameTests() {}

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 100)
    public static void hiddenFrameRetainsFiniteSableMassAndCenterOfMass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(framePos, state, Block.UPDATE_ALL), "could not place Mechanism Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        check(manager.setFrameShellMode(level, framePos, FrameShellMode.HIDDEN),
                "could not set HIDDEN shell mode");
        BlockState hidden = level.getBlockState(framePos);
        check(hidden.is(ModRegistries.MECHANISM_FRAME.get()), "HIDDEN replaced Mechanism Frame block");
        check(hidden.getValue(MechanismFrameBlock.SHELL_MODE) == FrameShellMode.HIDDEN,
                "Frame did not enter HIDDEN mode");
        check(hidden.getCollisionShape(level, framePos).isEmpty(),
                "regression fixture must have an empty collision shape");

        MassTracker tracker = MassTracker.build(level, new BoundingBox3i(
                framePos.getX(), framePos.getY(), framePos.getZ(),
                framePos.getX(), framePos.getY(), framePos.getZ()));
        Vector3dc center = tracker.getCenterOfMass();
        check(center != null, "Sable discarded non-colliding Frame and returned null center of mass");
        check(Double.isFinite(tracker.getMass())
                        && Math.abs(tracker.getMass() - ManagedFrameMassPolicy.FRAME_SHELL_MASS) < 1.0e-12,
                "Sable MassTracker did not retain configured Frame shell mass");
        check(Double.isFinite(center.x()) && Double.isFinite(center.y()) && Double.isFinite(center.z()),
                "Sable returned non-finite center of mass for HIDDEN Frame");
        check(Math.abs(center.x() - (framePos.getX() + 0.5)) < 1.0e-9
                        && Math.abs(center.y() - (framePos.getY() + 0.5)) < 1.0e-9
                        && Math.abs(center.z() - (framePos.getZ() + 0.5)) < 1.0e-9,
                "HIDDEN Frame center of mass moved away from its block center");
        helper.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
