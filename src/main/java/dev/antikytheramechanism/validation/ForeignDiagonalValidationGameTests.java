package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.compat.create.CreateContraptionKineticGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrapper for the downstream foreign-host Create diagonal regression. */
@GameTestHolder("antikytheramechanism_diagonal")
@PrefixGameTestTemplate(false)
public final class ForeignDiagonalValidationGameTests {
    private ForeignDiagonalValidationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void foreignDiagonal(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }
}
