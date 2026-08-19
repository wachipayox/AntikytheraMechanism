package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
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
        check(MechanismAssemblyHost.resolve(level, rootDestination).kind() == MechanismAssemblyHost.Kind.ROOT,
                "moved Frame did not resolve back to ROOT");
        check(host.isRemoved(),
                "Sable did not mark the emptied foreign source host removed after return move");
        helper.succeed();
    }

    /** A physical Frame without a valid SavedData owner must never be copied by Sable. */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void unownedPhysicalFrameFailsBeforeSableCopiesAnything(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos destination = helper.absolutePos(new BlockPos(6, 3, 3));
        placeFrame(level, source);
        check(level.getBlockState(destination).isAir(), "unowned-frame destination was not empty");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly expected = manager.getAssemblyAt(source).orElseThrow();
        var frameIndex = ((MechanismAssemblyManagerAccessor) (Object) manager)
                .antikytheramechanism$getFrameIndex();
        var removedOwner = frameIndex.remove(source);
        check(expected.id().equals(removedOwner), "fixture could not detach source Frame from frameIndex");

        boolean rejected = false;
        try {
            SubLevelAssemblyHelper.AssemblyTransform transform = new SubLevelAssemblyHelper.AssemblyTransform(
                    source,
                    destination,
                    0,
                    Rotation.NONE,
                    level);
            SubLevelAssemblyHelper.moveBlocks(level, transform, List.of(source));
        } catch (IllegalStateException expectedFailure) {
            rejected = true;
        } finally {
            // Restore the deliberately corrupted fixture before any assertion can fail so this test
            // cannot poison later GameTests even when the fail-closed path regresses.
            frameIndex.put(source.immutable(), expected.id());
        }

        check(rejected, "Sable move did not reject a physical Frame with no assembly owner");
        check(level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get()),
                "unowned source Frame was mutated before preflight rejection");
        check(level.getBlockState(destination).isAir(),
                "unowned source Frame reached destination before preflight rejection");
        check(manager.pendingContraptionMove(expected.id()).isEmpty(),
                "unowned-frame rejection created a relocation journal before owner validation");
        check(manager.getAssemblyAt(source).map(MechanismAssembly::id).orElse(null).equals(expected.id()),
                "fixture frameIndex was not restored after fail-closed assertion");
        helper.succeed();
    }

    /**
     * Sable catches failures between the per-block beforeMove and afterMove callbacks. Such a failure
     * must leave only the persistent relocation journal as authority: no thread-local destination
     * permission may survive and an unrelated later Sable move must remain independent.
     */
    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void failedFrameCopyCannotLeakDestinationAuthorization(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos failedSource = helper.absolutePos(new BlockPos(3, 3, 8));
        BlockPos failedDestination = helper.absolutePos(new BlockPos(6, 3, 8));
        BlockPos blockingFrame = helper.absolutePos(new BlockPos(7, 3, 8));
        BlockPos nextSource = helper.absolutePos(new BlockPos(10, 3, 8));
        BlockPos nextStone = nextSource.above();
        BlockPos nextRootDestination = helper.absolutePos(new BlockPos(13, 3, 8));
        BlockPos nextRootStoneDestination = nextRootDestination.above();

        placeFrame(level, failedSource);
        placeFrame(level, blockingFrame);
        placeFrame(level, nextSource);
        check(level.setBlock(nextStone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place independent post-fault Sable fixture stone");
        check(level.getBlockState(failedDestination).isAir(),
                "fault destination was not empty before Sable move");
        check(level.getBlockState(nextRootDestination).isAir()
                        && level.getBlockState(nextRootStoneDestination).isAir(),
                "post-fault ROOT destination was not empty");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly failedAssembly = manager.getAssemblyAt(failedSource).orElseThrow();
        MechanismAssembly blockerAssembly = manager.getAssemblyAt(blockingFrame).orElseThrow();
        MechanismAssembly nextAssembly = manager.getAssemblyAt(nextSource).orElseThrow();
        check(!failedAssembly.id().equals(blockerAssembly.id())
                        && !failedAssembly.id().equals(nextAssembly.id())
                        && !blockerAssembly.id().equals(nextAssembly.id()),
                "fault fixtures unexpectedly merged before the test");

        MechanismAssemblyManagerAccessor accessor = (MechanismAssemblyManagerAccessor) (Object) manager;
        var pendingMoves = accessor.antikytheramechanism$getPendingContraptionMoves();
        BlockState frameState = level.getBlockState(failedSource);
        boolean[] faultInjected = {false};
        ServerSubLevel[] postFaultHost = {null};

        try {
            AutoCloseable probeScope = SableFrameRelocationService.installBeforeMoveFaultProbe(
                    (probeLevel, source, destination) -> {
                        if (probeLevel == level
                                && source.equals(failedSource)
                                && destination.equals(failedDestination)) {
                            faultInjected[0] = true;
                            throw new IllegalStateException("intentional Sable post-beforeMove fault");
                        }
                    });
            try {
                SubLevelAssemblyHelper.AssemblyTransform failedTransform =
                        new SubLevelAssemblyHelper.AssemblyTransform(
                                failedSource,
                                failedDestination,
                                0,
                                Rotation.NONE,
                                level);
                // Sable 2.0.3 catches the injected exception inside its per-block copy loop. A
                // regression that lets it escape is itself a deterministic test failure.
                SubLevelAssemblyHelper.moveBlocks(level, failedTransform, List.of(failedSource));
            } finally {
                closeFaultProbe(probeScope);
            }

            check(faultInjected[0], "fault probe never ran after Mechanism Frame beforeMove bookkeeping");
            PendingContraptionMove failedJournal = manager.pendingContraptionMove(failedAssembly.id())
                    .orElseThrow(() -> new AssertionError(
                            "failed Sable copy lost its fail-closed relocation journal"));
            check(failedJournal.hasPlacement(),
                    "failed Sable copy retained only a source journal without its prepared destination");
            check(failedJournal.sourceFrames().contains(failedSource),
                    "failed Sable journal no longer covers its source Frame");
            check(failedJournal.targetFrames().contains(failedDestination),
                    "failed Sable journal no longer covers its destination Frame");
            check(manager.isPhysicalRelocationTransition(failedSource),
                    "failed Sable source is not protected by the retained relocation journal");
            check(manager.isPhysicalRelocationTransition(failedDestination),
                    "failed Sable destination is not protected by the retained relocation journal");
            check(manager.getAssemblyAt(failedSource)
                            .map(MechanismAssembly::id)
                            .orElse(null)
                            .equals(failedAssembly.id()),
                    "failed Sable copy moved logical ownership away from the source");
            check(manager.getAssemblyAt(failedDestination).isEmpty(),
                    "failed Sable copy committed destination ownership despite missing destination Frame");
            check(level.getBlockState(failedDestination).isAir(),
                    "faulted Frame was physically written before the injected post-beforeMove failure");

            // Make canPlaceFrame deterministically false for the historical destination while the
            // failed journal is temporarily absent. The adjacent blocker is given a source-only
            // contraption journal, so any true result from FrameMaskWriteGuard here can only come
            // from an out-of-band destination authorization. The old ACTIVE_DESTINATION leak would
            // have returned true at this exact assertion.
            PendingContraptionMove removedFailedJournal = pendingMoves.remove(failedAssembly.id());
            check(removedFailedJournal != null,
                    "could not temporarily remove retained failed journal for stale-authorization probe");
            PendingContraptionMove blockerLock = new PendingContraptionMove(
                    blockerAssembly.id(),
                    blockerAssembly.frames(),
                    blockerAssembly.origin(),
                    blockerAssembly.frames(),
                    blockerAssembly.poseTarget(),
                    level.getGameTime());
            PendingContraptionMove previousBlocker = pendingMoves.put(blockerAssembly.id(), blockerLock);
            check(previousBlocker == null, "blocking fixture unexpectedly already had a contraption journal");
            try {
                check(!manager.isPhysicalRelocationTransition(failedDestination),
                        "fault destination remained covered after its persistent journal was removed");
                check(!manager.canPlaceFrame(level, failedDestination),
                        "blocking fixture did not make canPlaceFrame fail at the historical destination");
                check(!FrameMaskWriteGuard.canWrite(level, failedDestination, frameState),
                        "historical Sable destination retained a transient placement authorization after the fault");
            } finally {
                pendingMoves.remove(blockerAssembly.id());
                pendingMoves.put(failedAssembly.id(), removedFailedJournal);
            }

            check(manager.pendingContraptionMove(failedAssembly.id()).isPresent(),
                    "stale-authorization probe did not restore the failed relocation journal");

            // A completely independent Sable operation must work immediately on the same server
            // thread. Exercise both ROOT -> FOREIGN and FOREIGN -> ROOT here; with the transient
            // bypass removed, both destination Frame writes can be admitted only by their prepared
            // PendingContraptionMove target journals.
            ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                    level,
                    nextSource,
                    List.of(nextSource, nextStone),
                    new BoundingBox3i(
                            nextSource.getX(), nextSource.getY(), nextSource.getZ(),
                            nextStone.getX(), nextStone.getY(), nextStone.getZ()));
            postFaultHost[0] = host;
            boolean hostLive = host != null && !host.isRemoved();
            BlockPos hostedFrame = hostLive ? host.getPlot().getCenterBlock() : BlockPos.ZERO;
            BlockPos hostedStone = hostedFrame.above();
            boolean rootToForeignFramePresent = hostLive
                    && level.getBlockState(hostedFrame).is(ModRegistries.MECHANISM_FRAME.get())
                    && level.getBlockEntity(hostedFrame) instanceof MechanismFrameBlockEntity;
            boolean rootToForeignStonePresent = hostLive && level.getBlockState(hostedStone).is(Blocks.STONE);
            boolean rootToForeignOwnershipSettled = hostLive
                    && manager.getAssemblyAt(hostedFrame)
                            .map(MechanismAssembly::id)
                            .orElse(null)
                            .equals(nextAssembly.id());
            boolean rootToForeignJournalCleared = manager.pendingContraptionMove(nextAssembly.id()).isEmpty();
            boolean failedJournalSurvivedNextOperation =
                    manager.pendingContraptionMove(failedAssembly.id()).isPresent();

            if (hostLive) {
                SubLevelAssemblyHelper.AssemblyTransform returnTransform =
                        new SubLevelAssemblyHelper.AssemblyTransform(
                                hostedFrame,
                                nextRootDestination,
                                0,
                                Rotation.NONE,
                                level);
                SubLevelAssemblyHelper.moveBlocks(
                        level,
                        returnTransform,
                        List.of(hostedFrame, hostedStone));
            }

            check(hostLive, "independent Sable operation failed to create a foreign host after fault");
            check(rootToForeignFramePresent,
                    "ROOT -> FOREIGN Frame placement failed without transient destination authorization");
            check(rootToForeignStonePresent,
                    "ROOT -> FOREIGN support block failed after faulted Sable move");
            check(rootToForeignOwnershipSettled,
                    "ROOT -> FOREIGN assembly ownership did not settle after faulted Sable move");
            check(rootToForeignJournalCleared,
                    "ROOT -> FOREIGN relocation journal remained pending after successful placement");
            check(failedJournalSurvivedNextOperation,
                    "independent Sable operation consumed or replaced the earlier fail-closed journal");
            check(level.getBlockState(nextRootDestination).is(ModRegistries.MECHANISM_FRAME.get()),
                    "FOREIGN -> ROOT Frame placement failed without transient destination authorization");
            check(level.getBlockState(nextRootStoneDestination).is(Blocks.STONE),
                    "FOREIGN -> ROOT support block failed after faulted Sable move");
            check(level.getBlockEntity(nextRootDestination) instanceof MechanismFrameBlockEntity,
                    "FOREIGN -> ROOT Frame BlockEntity did not settle after faulted Sable move");
            assertHealthySettlement(manager, nextAssembly, nextRootDestination, "post-fault foreign-to-root");
            check(host.isRemoved(),
                    "post-fault foreign host was not removed after FOREIGN -> ROOT cleanup");
            check(manager.pendingContraptionMove(failedAssembly.id()).isPresent(),
                    "successful subsequent Sable round trip altered the earlier fail-closed journal");

            helper.succeed();
        } finally {
            // If an assertion fails after the independent host was created, first try to empty it by
            // the same real Sable path used in normal teardown. Then remove all test-only manager
            // records before clearing root fixture blocks so this regression cannot poison later tests.
            ServerSubLevel host = postFaultHost[0];
            if (host != null && !host.isRemoved()) {
                BlockPos hostedFrame = host.getPlot().getCenterBlock();
                BlockPos hostedStone = hostedFrame.above();
                java.util.ArrayList<BlockPos> remaining = new java.util.ArrayList<>();
                if (!level.getBlockState(hostedFrame).isAir()) {
                    remaining.add(hostedFrame);
                }
                if (!level.getBlockState(hostedStone).isAir()) {
                    remaining.add(hostedStone);
                }
                if (!remaining.isEmpty()) {
                    try {
                        SubLevelAssemblyHelper.moveBlocks(
                                level,
                                new SubLevelAssemblyHelper.AssemblyTransform(
                                        hostedFrame,
                                        nextSource,
                                        0,
                                        Rotation.NONE,
                                        level),
                                remaining);
                    } catch (RuntimeException cleanupFailure) {
                        AntikytheraMechanism.LOGGER.error(
                                "Could not return post-fault foreign GameTest fixture through Sable cleanup",
                                cleanupFailure);
                    }
                }
            }

            List<java.util.UUID> cleanupIds = List.of(
                    failedAssembly.id(), blockerAssembly.id(), nextAssembly.id());
            cleanupIds.forEach(accessor.antikytheramechanism$getPendingContraptionMoves()::remove);
            cleanupIds.forEach(accessor.antikytheramechanism$getPendingFrameEvacuations()::remove);
            cleanupIds.forEach(accessor.antikytheramechanism$getContentRecoveryLocks()::remove);
            cleanupIds.forEach(accessor.antikytheramechanism$getInvalidContraptionMovesLogged()::remove);
            accessor.antikytheramechanism$getFrameIndex().entrySet()
                    .removeIf(entry -> cleanupIds.contains(entry.getValue()));
            cleanupIds.forEach(accessor.antikytheramechanism$getAssemblies()::remove);

            for (BlockPos position : List.of(
                    failedSource,
                    failedDestination,
                    blockingFrame,
                    nextSource,
                    nextStone,
                    nextRootDestination,
                    nextRootStoneDestination)) {
                if (!level.getBlockState(position).isAir()) {
                    FrameMaskWriteGuard.runBypassing(() ->
                            level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
                }
            }
        }
    }

    private static void closeFaultProbe(AutoCloseable probeScope) {
        try {
            probeScope.close();
        } catch (Exception exception) {
            throw new AssertionError("could not disarm Sable relocation fault probe", exception);
        }
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
