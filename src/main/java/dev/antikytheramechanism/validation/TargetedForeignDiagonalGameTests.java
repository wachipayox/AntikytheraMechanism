package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.compat.create.CreateContraptionKineticGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrappers for native diagonal Create transmission after Sable settlement. */
@GameTestHolder("antikytheramechanism_diagonal")
@PrefixGameTestTemplate(false)
public final class TargetedForeignDiagonalGameTests {
    private TargetedForeignDiagonalGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void rootDiagonal(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesUseNativeSmallToLargeCogRatio(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 260)
    public static void foreignDiagonal(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }
}
