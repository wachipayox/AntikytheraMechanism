package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Regression coverage for placement feedback emitted from Sable plot-backed mini content. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlacementFeedbackGameTests {
    private static final double FRAME_SOUND_RADIUS_SQUARED = 2.0 * 2.0;
    private static final double PLOT_SEPARATION_SQUARED = 32.0 * 32.0;

    private PlacementFeedbackGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void firstMiniPlacementSoundProjectsToPhysicalFrameImmediately(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        check(level.setBlock(
                        framePos,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState(),
                        Block.UPDATE_ALL),
                "could not place Mechanism Frame");

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create managed child for first placement");

        // This is deliberately computed before any user mini block exists. The first placement sound
        // must already be projectable even though a remote client may not have received/tracked this
        // just-created SubLevel yet.
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        Vec3 plotSound = Vec3.atCenterOf(miniGlobal);
        Vec3 physicalSound = AuthoritativePlacementSound.physicalSoundPosition(level, miniGlobal);
        Vec3 frameCenter = Vec3.atCenterOf(framePos);

        check(physicalSound.distanceToSqr(frameCenter) < FRAME_SOUND_RADIUS_SQUARED,
                "first mini placement sound did not project near its physical Frame: " + physicalSound);
        check(physicalSound.distanceToSqr(plotSound) > PLOT_SEPARATION_SQUARED,
                "first mini placement sound remained at Sable plot-storage coordinates");
        check(!AuthoritativePlacementSound.shouldCompensateForeignHostedManagedPlacement(level, miniGlobal),
                "root-hosted Frame was incorrectly classified as foreign-host sound compensation");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void foreignHostedMiniPlacementSoundUsesWorldSpaceCompensation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(4, 3, 4));
        check(level.setBlock(
                        rootFrame,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState(),
                        Block.UPDATE_ALL),
                "could not place root Mechanism Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly rootAssembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, rootAssembly);
        check(child != null && !child.isRemoved(), "could not create managed child before host assembly");

        BlockPos rootMiniLocal = MiniCoordinateMapper.frameToMini(rootAssembly, rootFrame, 0, 0, 0);
        BlockPos rootMiniGlobal = MechanismSubLevelService.toPlotPosition(child, rootMiniLocal);
        check(level.setBlock(rootMiniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed managed child before moving Frame into Sable host");

        BoundingBox3i bounds = BoundingBox3i.from(List.of(rootFrame));
        check(bounds != null, "could not compute Sable assembly bounds");
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame),
                bounds.expand(1, 1, 1));
        check(host != null && !host.isRemoved(), "ordinary unit-scale Sable host was not created");

        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly hostedAssembly = manager.getAssemblyAt(hostedFrame).orElseThrow();
        ServerSubLevel hostedChild = MechanismSubLevelService.findExisting(level, hostedAssembly);
        check(hostedChild != null && !hostedChild.isRemoved(), "managed child did not survive foreign hosting");

        BlockPos hostedMiniLocal = MiniCoordinateMapper.frameToMini(hostedAssembly, hostedFrame, 0, 0, 0);
        BlockPos hostedMiniGlobal = MechanismSubLevelService.toPlotPosition(hostedChild, hostedMiniLocal);
        check(AuthoritativePlacementSound.shouldCompensateForeignHostedManagedPlacement(
                        level, hostedMiniGlobal),
                "foreign-hosted mini placement was not classified for authoritative sound compensation");

        Vec3 physicalMiniSound = AuthoritativePlacementSound.physicalSoundPosition(level, hostedMiniGlobal);
        Vec3 physicalFrameCenter = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(hostedFrame));
        check(physicalMiniSound.distanceToSqr(physicalFrameCenter) < FRAME_SOUND_RADIUS_SQUARED,
                "foreign-hosted mini sound did not follow the physical hosted Frame");
        check(physicalMiniSound.distanceToSqr(Vec3.atCenterOf(hostedMiniGlobal)) > PLOT_SEPARATION_SQUARED,
                "foreign-hosted mini sound remained at managed-child plot-storage coordinates");
        helper.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
