package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrappers for synchronous Sable foreign-host settlement. */
@GameTestHolder("antikytheramechanism_settlement")
@PrefixGameTestTemplate(false)
public final class TargetedSableSettlementGameTests {
    private TargetedSableSettlementGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void foreignSettlement(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.independentFramesSettleIntoSameForeignHostSynchronously(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void foreignToRootRouting(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.foreignHostMoveBackToRootReadsPlotChunkSynchronously(helper);
    }
}
