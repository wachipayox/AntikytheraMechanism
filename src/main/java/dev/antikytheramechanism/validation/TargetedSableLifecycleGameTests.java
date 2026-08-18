package dev.antikytheramechanism.validation;

import dev.antikytheramechanism.compat.create.CreateContraptionKineticGameTests;
import dev.antikytheramechanism.frame.FrameDirectRemovalGameTests;
import dev.antikytheramechanism.sublevel.SableForeignFrameSettlementGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Validation-only wrappers around the production GameTests changed by the Sable lifecycle fixes. */
@GameTestHolder("antikytheramechanism_targeted")
@PrefixGameTestTemplate(false)
public final class TargetedSableLifecycleGameTests {
    private TargetedSableLifecycleGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void directAirOne(GameTestHelper helper) {
        FrameDirectRemovalGameTests.directAirReplacementDoesNotPoisonFrameCoordinate(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void directAirTwo(GameTestHelper helper) {
        FrameDirectRemovalGameTests.directAirReplacementDoesNotPoisonFrameCoordinate(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void directAirThree(GameTestHelper helper) {
        FrameDirectRemovalGameTests.directAirReplacementDoesNotPoisonFrameCoordinate(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void repeatedDirectAirCycles(GameTestHelper helper) {
        FrameDirectRemovalGameTests.repeatedDirectAirCyclesKeepCoordinateReusable(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void directAirOriginBridge(GameTestHelper helper) {
        FrameDirectRemovalGameTests.directAirOriginBridgeSplitsCleanlyAndCanBeReplaced(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void foreignSettlement(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.independentFramesSettleIntoSameForeignHostSynchronously(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void foreignToRootRouting(GameTestHelper helper) {
        SableForeignFrameSettlementGameTests.foreignHostMoveBackToRootReadsPlotChunkSynchronously(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void rootDiagonal(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesUseNativeSmallToLargeCogRatio(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void foreignDiagonalOne(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void foreignDiagonalTwo(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void foreignDiagonalThree(GameTestHelper helper) {
        CreateContraptionKineticGameTests.diagonalSeparateFramesInSameForeignHostUseNativeCogRatio(helper);
    }
}
