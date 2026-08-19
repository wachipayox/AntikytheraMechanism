package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Temporary validation-only alias for the deterministic post-beforeMove fault regression. */
@GameTestHolder("antikythera_sable_fault_validation")
@PrefixGameTestTemplate(false)
public final class TargetedSableFaultGameTests {
    private TargetedSableFaultGameTests() {}

    @GameTest(templateNamespace = AntikytheraMechanism.MOD_ID, template = "frame_rotation_empty", timeoutTicks = 220)
    public static void failedFrameCopyCannotLeakDestinationAuthorization(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.failedFrameCopyCannotLeakDestinationAuthorization(helper);
    }
}
