package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for a foreign host transitioning from macro-supported to Frame-only. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenFrameFrameOnlyTransitionGameTests {
    private HiddenFrameFrameOnlyTransitionGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 260)
    public static void removingSharedMacroSupportExposesDisconnectedFrameOnlyAssemblies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceSupport = helper.absolutePos(new BlockPos(4, 4, 4));
        check(level.setBlock(sourceSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place shared macro support");

        List<BlockPos> sourceFrames = List.of(
                sourceSupport.north(),
                sourceSupport.east(),
                sourceSupport.south(),
                sourceSupport.west());
        for (BlockPos frame : sourceFrames) {
            placeFrame(level, frame);
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<MechanismAssembly> sourceAssemblies = new ArrayList<>();
        Set<UUID> sourceIds = new HashSet<>();
        for (BlockPos frame : sourceFrames) {
            MechanismAssembly assembly = manager.getAssemblyAt(frame)
                    .orElseThrow(() -> new AssertionError("missing source Frame assembly at " + frame));
            sourceAssemblies.add(assembly);
            sourceIds.add(assembly.id());
        }
        check(sourceIds.size() == sourceFrames.size(),
                "cardinal Frames unexpectedly merged before foreign hosting");

        // Every mini touches the central full macro block, but the four minis do not touch one another.
        // Removing the macro block therefore turns one supported host into four disconnected mini islands.
        seedMini(level, sourceAssemblies.get(0), sourceFrames.get(0), 0, 0, 1); // north -> support
        seedMini(level, sourceAssemblies.get(1), sourceFrames.get(1), 0, 0, 0); // east -> support
        seedMini(level, sourceAssemblies.get(2), sourceFrames.get(2), 1, 0, 0); // south -> support
        seedMini(level, sourceAssemblies.get(3), sourceFrames.get(3), 1, 0, 1); // west -> support

        List<BlockPos> movedBlocks = new ArrayList<>(sourceFrames);
        movedBlocks.add(sourceSupport);
        BoundingBox3i bounds = BoundingBox3i.from(movedBlocks);
        check(bounds != null, "could not compute shared-host bounds");
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                sourceSupport,
                movedBlocks,
                bounds.expand(1, 1, 1));
        check(host != null && !host.isRemoved(), "Sable did not create shared foreign host");

        BlockPos hostedSupport = host.getPlot().getCenterBlock();
        List<BlockPos> hostedFrames = List.of(
                hostedSupport.north(),
                hostedSupport.east(),
                hostedSupport.south(),
                hostedSupport.west());
        List<MechanismAssembly> hostedAssemblies = new ArrayList<>();
        for (int index = 0; index < hostedFrames.size(); index++) {
            BlockPos frame = hostedFrames.get(index);
            MechanismAssembly moved = manager.getAssemblyAt(frame)
                    .orElseThrow(() -> new AssertionError("Frame assembly did not follow host at " + frame));
            check(moved.id().equals(sourceAssemblies.get(index).id()),
                    "Frame assembly UUID changed while entering shared host at " + frame);
            hostedAssemblies.add(moved);
            check(manager.setFrameShellMode(level, frame, FrameShellMode.HIDDEN),
                    "could not hide hosted Frame at " + frame);
            HiddenFrameGeometryPolicy.request(level, moved.id());
        }

        HiddenFrameGeometryPolicy.tick(level);
        for (BlockPos frame : hostedFrames) {
            assertMode(level, frame, FrameShellMode.HIDDEN,
                    "macro-supported Frame could not start hidden");
        }

        // This write must itself dirty the hidden peers. No second placement or safety sweep is allowed
        // to be necessary for the visibility correction.
        check(level.removeBlock(hostedSupport, false), "could not remove shared macro support");
        HiddenFrameGeometryPolicy.tick(level);

        for (BlockPos frame : hostedFrames) {
            assertMode(level, frame, FrameShellMode.NORMAL,
                    "disconnected Frame-only island stayed hidden after removing the last macro support");
        }
        helper.succeed();
    }

    private static void seedMini(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos frame,
            int x,
            int y,
            int z) {
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create managed child for " + assembly.id());
        BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
        check(level.setBlock(global, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini content for " + assembly.id());
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        check(level.setBlock(
                        position,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState(),
                        Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static void assertMode(
            ServerLevel level,
            BlockPos frame,
            FrameShellMode expected,
            String message) {
        BlockState state = level.getBlockState(frame);
        check(state.is(ModRegistries.MECHANISM_FRAME.get()), message + ": Frame disappeared at " + frame);
        check(state.getValue(MechanismFrameBlock.SHELL_MODE) == expected,
                message + ": mode=" + state.getValue(MechanismFrameBlock.SHELL_MODE)
                        + ", expected=" + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
