package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrapper for isolated FOREIGN -> ROOT settlement repetition. */
@GameTestHolder("antikytheramechanism_return")
@PrefixGameTestTemplate(false)
public final class ForeignReturnValidationGameTests {
    private ForeignReturnValidationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void foreignToRoot(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.foreignHostMoveBackToRootReadsPlotChunkSynchronously(helper);
    }
}
