package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.compat.create.HiddenFrameContraptionGeometryGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Temporary CI-only wrapper. Never integrate this file. */
@GameTestHolder("antikytheramechanism_hidden_geometry_validation")
@PrefixGameTestTemplate(false)
public final class HiddenFlightGeometryFocusedGameTests {
    private HiddenFlightGeometryFocusedGameTests() {
    }

    @GameTest(template = "antikytheramechanism:frame_rotation_empty", timeoutTicks = 240)
    public static void breakingHiddenMiniBridgeDisassemblesWithoutSailDeficit(GameTestHelper helper) {
        HiddenFrameContraptionGeometryGameTests.breakingHiddenMiniBridgeDisassemblesWithoutSailDeficit(helper);
    }
}
