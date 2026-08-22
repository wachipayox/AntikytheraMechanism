package dev.antikytheramechanism;

import dev.antikytheramechanism.compat.create.MiniSailBearingGameTests;
import dev.antikytheramechanism.sublevel.HostedMiniAerodynamicGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Temporary isolated wrappers used only while validating hidden-only mini-sail semantics. */
@GameTestHolder(HiddenMiniSailValidationGameTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class HiddenMiniSailValidationGameTests {
    public static final String NAMESPACE = "antikytheramechanism_hidden_mini_sails_validation";

    private HiddenMiniSailValidationGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void windmill(GameTestHelper helper) {
        MiniSailBearingGameTests.windmillUsesDynamicMiniAreaAndDisassemblesBelowMinimum(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void propeller(GameTestHelper helper) {
        MiniSailBearingGameTests.propellerUsesFractionalMiniGeometryAndDisassemblesBelowMinimum(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void gyroscopicPropeller(GameTestHelper helper) {
        MiniSailBearingGameTests.gyroscopicPropellerDisassemblesBelowDynamicMinimum(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void shellExposure(GameTestHelper helper) {
        MiniSailBearingGameTests.onlyHiddenFramesExposeMiniSailsAndShellChangeDisassembles(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void hostedCreateAerodynamics(GameTestHelper helper) {
        HostedMiniAerodynamicGameTests.createMiniSailUsesQuarterAreaRealPositionAndOrientation(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 170)
    public static void rootAerodynamics(GameTestHelper helper) {
        HostedMiniAerodynamicGameTests.rootManagedMiniSailDoesNotCreateIndependentRigidPhysics(helper);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void symmetricAerodynamics(GameTestHelper helper) {
        HostedMiniAerodynamicGameTests.symmetricMiniSailKeepsItsOwnSableAerodynamics(helper);
    }
}
