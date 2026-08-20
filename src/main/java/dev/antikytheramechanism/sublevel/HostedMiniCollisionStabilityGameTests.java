package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Regression coverage for resting hosted-mini colliders remaining native-state stable. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostedMiniCollisionStabilityGameTests {
    private HostedMiniCollisionStabilityGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void unchangedHostedProxyDoesNotMutateRapierEverySubstep(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();

        BlockPos hostedFrame = setup.host().getPlot().getCenterBlock();
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                setup.assembly(), hostedFrame, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(setup.child(), miniLocal);
        check(level.setBlock(miniGlobal, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not add hosted mini collision payload");

        setup.child().updateMergedMassData(0.0f);
        setup.host().updateMergedMassData(0.0f);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();

        HostedMiniCollisionBridge.reconcile(level, pipeline, manager);
        HostedMiniCollisionBridge.NativeMutationCounts first =
                HostedMiniCollisionBridge.nativeMutationCounts(level, setup.assembly().id());
        check(first.transformUploads() > 0L,
                "first reconciliation did not upload the mounted proxy transform");
        check(first.childBoundsUploads() > 0L,
                "first reconciliation did not suppress the managed child collider");

        // A second pre-solver reconciliation with absolutely no state change represents the next
        // physics substep of a resting vehicle. It must not mark the proxy PARENT changed or replace
        // the child LevelCollider shape again.
        HostedMiniCollisionBridge.reconcile(level, pipeline, manager);
        HostedMiniCollisionBridge.NativeMutationCounts second =
                HostedMiniCollisionBridge.nativeMutationCounts(level, setup.assembly().id());

        check(second.transformUploads() == first.transformUploads(),
                "unchanged hosted proxy rewrote its Rapier parent transform");
        check(second.childBoundsUploads() == first.childBoundsUploads(),
                "unchanged managed child rewrote its Rapier sentinel bounds");
        helper.succeed();
    }

    private static HostedSetup createHostedSetup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(rootFrame, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed child");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame),
                new BoundingBox3i(
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ(),
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create foreign host");

        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(hostedFrame).orElseThrow();
        check(moved.id().equals(assembly.id()), "logical assembly did not follow Sable host move");
        return new HostedSetup(moved, child, host);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record HostedSetup(
            MechanismAssembly assembly,
            ServerSubLevel child,
            ServerSubLevel host) {
    }
}
