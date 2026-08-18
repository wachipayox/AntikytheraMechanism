package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Verifies that ordinary Sable hosting fully settles independent Frame ownership before callers continue. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SableForeignFrameSettlementGameTests {
    private SableForeignFrameSettlementGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void independentFramesSettleIntoSameForeignHostSynchronously(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceRoot = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos targetRoot = sourceRoot.offset(0, 1, 1);
        BlockPos bridgeRoot = sourceRoot.above();
        placeFrame(level, sourceRoot);
        placeFrame(level, targetRoot);
        check(level.setBlock(bridgeRoot, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place foreign-host bridge");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceBefore = manager.getAssemblyAt(sourceRoot).orElseThrow();
        MechanismAssembly targetBefore = manager.getAssemblyAt(targetRoot).orElseThrow();
        check(!sourceBefore.id().equals(targetBefore.id()), "fixture Frames merged before hosting");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                sourceRoot,
                List.of(sourceRoot, bridgeRoot, targetRoot),
                new BoundingBox3i(
                        sourceRoot.getX(), sourceRoot.getY(), sourceRoot.getZ(),
                        targetRoot.getX(), targetRoot.getY(), targetRoot.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not return a live foreign host");

        BlockPos hostedSource = host.getPlot().getCenterBlock();
        BlockPos hostedBridge = hostedSource.above();
        BlockPos hostedTarget = hostedSource.offset(0, 1, 1);
        check(level.getBlockState(hostedSource).is(ModRegistries.MECHANISM_FRAME.get()),
                "source outer Frame state did not reach foreign host");
        check(level.getBlockState(hostedTarget).is(ModRegistries.MECHANISM_FRAME.get()),
                "target outer Frame state did not reach foreign host");
        check(level.getBlockState(hostedBridge).is(Blocks.STONE),
                "bridge block did not reach foreign host");
        check(level.getBlockEntity(hostedSource) instanceof MechanismFrameBlockEntity,
                "source Frame BlockEntity did not reach foreign host");
        check(level.getBlockEntity(hostedTarget) instanceof MechanismFrameBlockEntity,
                "target Frame BlockEntity did not reach foreign host");

        assertHealthySettlement(manager, sourceBefore, hostedSource, "source");
        assertHealthySettlement(manager, targetBefore, hostedTarget, "target");

        MechanismAssembly sourceAfter = manager.getAssemblyAt(hostedSource)
                .orElseThrow(() -> new AssertionError("source frameIndex did not adopt foreign-host coordinate"));
        MechanismAssembly targetAfter = manager.getAssemblyAt(hostedTarget)
                .orElseThrow(() -> new AssertionError("target frameIndex did not adopt foreign-host coordinate"));
        check(!sourceAfter.id().equals(targetAfter.id()), "independent Frames merged during hosting");

        MechanismAssemblyHost.Resolution sourceHost = MechanismAssemblyHost.resolve(level, hostedSource);
        MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(level, hostedTarget);
        check(sourceHost.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "source did not resolve as FOREIGN after hosting: " + sourceHost.kind());
        check(targetHost.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "target did not resolve as FOREIGN after hosting: " + targetHost.kind());
        check(host.getUniqueId().equals(sourceHost.foreignId()),
                "source foreign host UUID differs from Sable host");
        check(host.getUniqueId().equals(targetHost.foreignId()),
                "target foreign host UUID differs from Sable host");
        check(MechanismAssemblyHost.sameResolvedHost(level, hostedSource, hostedTarget),
                "hosted Frames do not resolve to the same foreign host");

        // This test owns the synthetic foreign host. Return every carried block through Sable's real
        // moveBlocks path before succeeding so the next GameTest cannot inherit a live plot, physics
        // binding or stale Frame index from this fixture. The synchronous settlement assertions above
        // have already run; this round trip is teardown, not a delay used to make them pass.
        SubLevelAssemblyHelper.AssemblyTransform cleanupTransform =
                new SubLevelAssemblyHelper.AssemblyTransform(
                        hostedSource,
                        sourceRoot,
                        0,
                        Rotation.NONE,
                        level);
        SubLevelAssemblyHelper.moveBlocks(
                level,
                cleanupTransform,
                List.of(hostedSource, hostedBridge, hostedTarget));

        check(level.getBlockState(sourceRoot).is(ModRegistries.MECHANISM_FRAME.get()),
                "source Frame did not return to ROOT during fixture cleanup");
        check(level.getBlockState(bridgeRoot).is(Blocks.STONE),
                "bridge did not return to ROOT during fixture cleanup");
        check(level.getBlockState(targetRoot).is(ModRegistries.MECHANISM_FRAME.get()),
                "target Frame did not return to ROOT during fixture cleanup");
        assertHealthySettlement(manager, sourceBefore, sourceRoot, "source cleanup");
        assertHealthySettlement(manager, targetBefore, targetRoot, "target cleanup");
        check(manager.getAssemblyAt(hostedSource).isEmpty()
                        && manager.getAssemblyAt(hostedTarget).isEmpty(),
                "fixture cleanup left stale foreign frameIndex ownership");
        check(MechanismAssemblyHost.resolve(level, sourceRoot).kind() == MechanismAssemblyHost.Kind.ROOT,
                "source fixture did not resolve back to ROOT");
        check(MechanismAssemblyHost.resolve(level, targetRoot).kind() == MechanismAssemblyHost.Kind.ROOT,
                "target fixture did not resolve back to ROOT");
        check(host.isRemoved(),
                "Sable did not mark the emptied foreign fixture host removed during cleanup");
        helper.succeed();
    }

    /**
     * Regression for the read side of Sable 2.0.3 LevelAccelerator routing. A plot position can have
     * a root-world LevelChunk visible at the same chunk coordinates; moveBlocks must read the actual
     * foreign-host plot state and not silently copy AIR to the root destination.
     */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void foreignHostMoveBackToRootReadsPlotChunkSynchronously(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos initialFrame = helper.absolutePos(new BlockPos(9, 3, 3));
        BlockPos initialStone = initialFrame.above();
        placeFrame(level, initialFrame);
        check(level.setBlock(initialStone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place root stone fixture");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly before = manager.getAssemblyAt(initialFrame).orElseThrow();

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                initialFrame,
                List.of(initialFrame, initialStone),
                new BoundingBox3i(
                        initialFrame.getX(), initialFrame.getY(), initialFrame.getZ(),
                        initialStone.getX(), initialStone.getY(), initialStone.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create foreign source host");

        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        BlockPos hostedStone = hostedFrame.above();
        check(level.getBlockState(hostedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "fixture Frame did not enter foreign host");
        check(level.getBlockState(hostedStone).is(Blocks.STONE),
                "fixture stone did not enter foreign host");
        check(level.getBlockEntity(hostedFrame) instanceof MechanismFrameBlockEntity,
                "fixture Frame BlockEntity did not enter foreign host");
        assertHealthySettlement(manager, before, hostedFrame, "foreign source");
        MechanismAssemblyHost.Resolution hostedResolution = MechanismAssemblyHost.resolve(level, hostedFrame);
        check(hostedResolution.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "fixture Frame did not resolve as FOREIGN before return move");
        check(host.getUniqueId().equals(hostedResolution.foreignId()),
                "fixture Frame resolved to the wrong foreign host before return move");

        BlockPos rootDestination = helper.absolutePos(new BlockPos(12, 3, 9));
        BlockPos rootStoneDestination = rootDestination.above();
        check(level.getBlockState(rootDestination).isAir() && level.getBlockState(rootStoneDestination).isAir(),
                "foreign-to-root destination was not empty");

        SubLevelAssemblyHelper.AssemblyTransform transform = new SubLevelAssemblyHelper.AssemblyTransform(
                hostedFrame,
                rootDestination,
                0,
                Rotation.NONE,
                level);
        SubLevelAssemblyHelper.moveBlocks(level, transform, List.of(hostedFrame, hostedStone));

        check(level.getBlockState(rootDestination).is(ModRegistries.MECHANISM_FRAME.get()),
                "foreign source Frame was read as AIR or written to the wrong root chunk");
        check(level.getBlockState(rootStoneDestination).is(Blocks.STONE),
                "foreign source stone was read as AIR or written to the wrong root chunk");
        check(level.getBlockState(hostedFrame).isAir(),
                "foreign source Frame was not cleared from the routed plot chunk");
        check(level.getBlockState(hostedStone).isAir(),
                "foreign source stone was not cleared from the routed plot chunk");
        check(level.getBlockEntity(rootDestination) instanceof MechanismFrameBlockEntity,
                "foreign source Frame BlockEntity did not reach root destination");

        assertHealthySettlement(manager, before, rootDestination, "foreign-to-root");
        check(manager.getAssemblyAt(hostedFrame).isEmpty(),
                "foreign-to-root move left stale frameIndex ownership at plot source");
        check(MechanismAssemblyHost.resolve(level, rootDestination).kind() == MechanismAssemblyHost.Kind.ROOT,
                "moved Frame did not resolve back to ROOT");
        check(host.isRemoved(),
                "Sable did not mark the emptied foreign source host removed after return move");
        helper.succeed();
    }

    private static void assertHealthySettlement(
            MechanismAssemblyManager manager,
            MechanismAssembly expected,
            BlockPos position,
            String label) {
        MechanismAssembly actual = manager.getAssemblyAt(position)
                .orElseThrow(() -> new AssertionError(label + " Frame ownership was not settled"));
        check(actual.id().equals(expected.id()), label + " assembly UUID changed during Sable movement");
        check(manager.pendingContraptionMove(expected.id()).isEmpty(),
                label + " relocation journal remained pending");
        check(!manager.isContentRecoveryLocked(expected.id()),
                label + " assembly became recovery-locked");
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
