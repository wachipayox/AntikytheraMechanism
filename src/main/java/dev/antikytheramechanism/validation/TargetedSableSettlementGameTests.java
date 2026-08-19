package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Temporary validation-only aliases for the Sable settlement regression suite. */
@GameTestHolder("antikythera_settlement_validation")
@PrefixGameTestTemplate(false)
public final class TargetedSableSettlementGameTests {
    private TargetedSableSettlementGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void independentFramesSettleIntoSameForeignHostSynchronously(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.independentFramesSettleIntoSameForeignHostSynchronously(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void foreignHostMoveBackToRootReadsPlotChunkSynchronously(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.foreignHostMoveBackToRootReadsPlotChunkSynchronously(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void unownedPhysicalFrameFailsBeforeSableCopiesAnything(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.unownedPhysicalFrameFailsBeforeSableCopiesAnything(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void failedFrameCopyCannotLeakDestinationAuthorization(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.failedFrameCopyCannotLeakDestinationAuthorization(helper);
    }
}
