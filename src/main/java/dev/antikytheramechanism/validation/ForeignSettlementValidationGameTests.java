package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrapper for isolated ROOT -> FOREIGN settlement repetition. */
@GameTestHolder("antikytheramechanism_settlement")
@PrefixGameTestTemplate(false)
public final class ForeignSettlementValidationGameTests {
    private ForeignSettlementValidationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void foreignSettlement(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.independentFramesSettleIntoSameForeignHostSynchronously(helper);
    }
}
