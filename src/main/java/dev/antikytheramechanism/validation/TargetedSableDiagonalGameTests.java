package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionKineticGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Temporary validation-only alias for the diagonal FOREIGN cleanup regression. */
@GameTestHolder("antikythera_diagonal_validation")
@PrefixGameTestTemplate(false)
public final class TargetedSableDiagonalGameTests {
    private TargetedSableDiagonalGameTests() {}

    @GameTest(templateNamespace = AntikytheraMechanism.MOD_ID, template = "frame_rotation_empty", timeoutTicks = 220)
    public static void diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }
}
