package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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

import java.util.Set;
import java.util.UUID;

@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyOriginRebaseFaultGameTests {
    private AssemblyOriginRebaseFaultGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void outboundFailureBeforeWriteFailsClosed(GameTestHelper helper) {
        verifyFault(helper, Fault.OUTBOUND_BEFORE);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void outboundPostFailureRollsBackPayload(GameTestHelper helper) {
        verifyFault(helper, Fault.OUTBOUND_ROLLED_BACK);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void outboundRollbackFailureRequiresRecovery(GameTestHelper helper) {
        verifyFault(helper, Fault.OUTBOUND_RECOVERY);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void inboundFailureBeforeWriteRetainsStagedPayload(GameTestHelper helper) {
        verifyFault(helper, Fault.INBOUND_BEFORE);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void inboundPostFailureRollsBackToStaging(GameTestHelper helper) {
        verifyFault(helper, Fault.INBOUND_ROLLED_BACK);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void inboundRollbackFailureRequiresRecovery(GameTestHelper helper) {
        verifyFault(helper, Fault.INBOUND_RECOVERY);
    }

    private static void verifyFault(GameTestHelper helper, Fault fault) {
        ServerLevel level = helper.getLevel();
        BlockPos oldOrigin = helper.absolutePos(new BlockPos(3, 3, 5));
        BlockPos second = oldOrigin.east();
        BlockPos third = second.east();
        placeFrame(level, oldOrigin);
        placeFrame(level, second);
        placeFrame(level, third);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(oldOrigin).orElseThrow();
        UUID sourceId = source.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, source);
        check(child != null && !child.isRemoved(), "could not materialize fault-injection payload");
        BlockPos local = MiniCoordinateMapper.frameToMini(source, second, 0, 0, 0);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(local, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed fault-injection payload");
        child.getPlot().updateBoundingBox();

        FaultListener listener = new FaultListener(fault);
        try (AssemblyLifecycleEvents.Registration ignored = AssemblyLifecycleEvents.register(
                AntikytheraMechanism.id("gametest_origin_rebase_" + fault.name().toLowerCase()), listener)) {
            check(level.destroyBlock(oldOrigin, false), "could not remove stale origin in fault scenario " + fault);
        }

        MechanismAssembly surviving = manager.getAssemblyAt(second).orElseThrow();
        check(surviving.id().equals(sourceId), "fault path changed authoritative assembly UUID");
        check(manager.getAssemblyAt(third).orElseThrow().id().equals(sourceId), "fault path split frameIndex ownership");
        check(surviving.frames().equals(Set.of(second, third)), "fault path changed retained Frame set");
        check(manager.isContentRecoveryLocked(sourceId), "failed origin rebase was not recovery-locked");
        check(listener.beforeCalls >= (fault.inbound ? 2 : 1), "expected transfer phase was not reached for " + fault);

        long stagingCount = manager.assemblies().stream()
                .filter(candidate -> !candidate.id().equals(sourceId) && candidate.frames().equals(Set.of(second, third)))
                .count();
        if (fault == Fault.OUTBOUND_BEFORE || fault == Fault.OUTBOUND_ROLLED_BACK) {
            check(stagingCount == 0, "compensated outbound failure leaked staging assembly");
        } else {
            check(stagingCount == 1, "recovery path did not retain exactly one staging assembly");
            MechanismAssembly staging = manager.assemblies().stream()
                    .filter(candidate -> !candidate.id().equals(sourceId) && candidate.frames().equals(Set.of(second, third)))
                    .findFirst().orElseThrow();
            check(manager.isContentRecoveryLocked(staging.id()), "retained staging assembly lacks recovery lock");
        }

        int copies = payloadCopies(level, manager, sourceId, second);
        check(copies == 1, "fault path left " + copies + " payload copies instead of exactly one");
        if (fault.inbound) {
            check(surviving.origin().equals(second) && surviving.frames().contains(surviving.origin()),
                    "inbound recovery path left source with invalid origin");
        } else {
            check(!surviving.frames().contains(surviving.origin()),
                    "outbound failure unexpectedly treated stale origin as repaired happy path");
        }
        helper.succeed();
    }

    private static int payloadCopies(ServerLevel level, MechanismAssemblyManager manager, UUID sourceId, BlockPos frame) {
        int copies = 0;
        for (MechanismAssembly candidate : manager.assemblies()) {
            if (!candidate.id().equals(sourceId) && !candidate.frames().contains(frame)) continue;
            if (candidate.id().equals(sourceId) && !candidate.frames().contains(frame)) continue;
            ServerSubLevel child = MechanismSubLevelService.findExisting(level, candidate);
            if (child == null || child.isRemoved()) continue;
            BlockPos local = MiniCoordinateMapper.frameToMini(candidate, frame, 0, 0, 0);
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            if (level.getBlockState(global).is(Blocks.GOLD_BLOCK)) copies++;
        }
        return copies;
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Fault {
        OUTBOUND_BEFORE(false, 1, true, true),
        OUTBOUND_ROLLED_BACK(false, 1, false, true),
        OUTBOUND_RECOVERY(false, 1, false, false),
        INBOUND_BEFORE(true, 2, true, true),
        INBOUND_ROLLED_BACK(true, 2, false, true),
        INBOUND_RECOVERY(true, 2, false, false);

        final boolean inbound;
        final int failTransfer;
        final boolean failBefore;
        final boolean rollbackSucceeds;

        Fault(boolean inbound, int failTransfer, boolean failBefore, boolean rollbackSucceeds) {
            this.inbound = inbound;
            this.failTransfer = failTransfer;
            this.failBefore = failBefore;
            this.rollbackSucceeds = rollbackSucceeds;
        }
    }

    private static final class FaultListener implements AssemblyLifecycleListener {
        private final Fault fault;
        private int beforeCalls;

        private FaultListener(Fault fault) {
            this.fault = fault;
        }

        @Override
        public boolean beforeAssemblyTransfer(AssemblyTransferContext context) {
            beforeCalls++;
            return !(beforeCalls == fault.failTransfer && fault.failBefore);
        }

        @Override
        public boolean afterAssemblyTransfer(AssemblyTransferContext context) {
            return !(beforeCalls == fault.failTransfer && !fault.failBefore);
        }

        @Override
        public boolean onAssemblyTransferRollback(AssemblyTransferContext context, boolean contentRestored) {
            return beforeCalls != fault.failTransfer || fault.rollbackSucceeds;
        }
    }
}
