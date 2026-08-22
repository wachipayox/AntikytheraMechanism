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

import java.util.List;

/** Regression coverage for foreign Sable hosts whose complete block payload consists of Frames. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenFrameWholeHostGameTests {
    private HiddenFrameWholeHostGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void singleFrameCanHideWhenItIsTheWholeForeignHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(4, 4, 4));
        placeFrame(level, sourceFrame);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing source Frame assembly"));
        ServerSubLevel child = ensureAndSeedMini(level, source, sourceFrame, 0, 0, 0);
        check(child != null, "could not seed managed child");

        ServerSubLevel host = assembleFrameOnlyHost(level, sourceFrame, List.of(sourceFrame));
        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(hostedFrame)
                .orElseThrow(() -> new AssertionError("Frame assembly did not follow foreign host"));
        check(moved.id().equals(source.id()), "Frame-only host changed assembly UUID");

        hideAndEvaluate(level, manager, moved, hostedFrame);
        assertMode(level, hostedFrame, FrameShellMode.HIDDEN,
                "a non-empty Frame that is the complete foreign host was forced visible");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void multipleAssembliesCanHideWhenTogetherTheyAreTheWholeForeignHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceA = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos sourceB = sourceA.offset(1, 0, 1); // diagonal: distinct Frame assemblies
        placeFrame(level, sourceA);
        placeFrame(level, sourceB);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assemblyA = manager.getAssemblyAt(sourceA)
                .orElseThrow(() -> new AssertionError("missing first Frame assembly"));
        MechanismAssembly assemblyB = manager.getAssemblyAt(sourceB)
                .orElseThrow(() -> new AssertionError("missing second Frame assembly"));
        check(!assemblyA.id().equals(assemblyB.id()),
                "diagonal source Frames unexpectedly merged into one assembly");

        // The chosen cells touch diagonally in the shared physical half-block lattice. Each assembly
        // is non-empty and the two payloads form a visually continuous Frame-only body.
        ensureAndSeedMini(level, assemblyA, sourceA, 1, 0, 1);
        ensureAndSeedMini(level, assemblyB, sourceB, 0, 0, 0);

        ServerSubLevel host = assembleFrameOnlyHost(level, sourceA, List.of(sourceA, sourceB));
        BlockPos hostedA = host.getPlot().getCenterBlock();
        BlockPos hostedB = hostedA.offset(1, 0, 1);
        MechanismAssembly movedA = manager.getAssemblyAt(hostedA)
                .orElseThrow(() -> new AssertionError("first assembly did not follow shared host"));
        MechanismAssembly movedB = manager.getAssemblyAt(hostedB)
                .orElseThrow(() -> new AssertionError("second assembly did not follow shared host"));
        check(movedA.id().equals(assemblyA.id()), "first assembly UUID changed while hosting");
        check(movedB.id().equals(assemblyB.id()), "second assembly UUID changed while hosting");

        check(manager.setFrameShellMode(level, hostedA, FrameShellMode.HIDDEN),
                "could not hide first Frame-only-host assembly");
        check(manager.setFrameShellMode(level, hostedB, FrameShellMode.HIDDEN),
                "could not hide second Frame-only-host assembly");
        HiddenFrameGeometryPolicy.request(level, movedA.id());
        HiddenFrameGeometryPolicy.request(level, movedB.id());
        HiddenFrameGeometryPolicy.tick(level);

        assertMode(level, hostedA, FrameShellMode.HIDDEN,
                "first assembly was forced visible despite the host containing only Frames");
        assertMode(level, hostedB, FrameShellMode.HIDDEN,
                "second assembly was forced visible despite the host containing only Frames");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 220)
    public static void completelyEmptyAssemblyCannotHideInsideFrameOnlyForeignHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceA = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos sourceB = sourceA.offset(1, 0, 1);
        placeFrame(level, sourceA);
        placeFrame(level, sourceB);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assemblyA = manager.getAssemblyAt(sourceA)
                .orElseThrow(() -> new AssertionError("missing populated Frame assembly"));
        MechanismAssembly assemblyB = manager.getAssemblyAt(sourceB)
                .orElseThrow(() -> new AssertionError("missing empty Frame assembly"));
        check(!assemblyA.id().equals(assemblyB.id()),
                "test requires two independent Frame assemblies");

        ensureAndSeedMini(level, assemblyA, sourceA, 1, 0, 1);
        // assemblyB intentionally receives no managed mini content at all.

        ServerSubLevel host = assembleFrameOnlyHost(level, sourceA, List.of(sourceA, sourceB));
        BlockPos hostedA = host.getPlot().getCenterBlock();
        BlockPos hostedB = hostedA.offset(1, 0, 1);
        MechanismAssembly movedA = manager.getAssemblyAt(hostedA)
                .orElseThrow(() -> new AssertionError("populated assembly did not follow shared host"));
        MechanismAssembly movedB = manager.getAssemblyAt(hostedB)
                .orElseThrow(() -> new AssertionError("empty assembly did not follow shared host"));

        check(manager.setFrameShellMode(level, hostedA, FrameShellMode.HIDDEN),
                "could not hide populated assembly for policy test");
        check(manager.setFrameShellMode(level, hostedB, FrameShellMode.HIDDEN),
                "could not request HIDDEN on empty assembly for policy test");
        HiddenFrameGeometryPolicy.request(level, movedA.id());
        HiddenFrameGeometryPolicy.request(level, movedB.id());
        HiddenFrameGeometryPolicy.tick(level);

        assertMode(level, hostedA, FrameShellMode.HIDDEN,
                "non-empty assembly lost the Frame-only-host exemption because a peer was empty");
        assertMode(level, hostedB, FrameShellMode.NORMAL,
                "completely empty assembly stayed hidden inside a Frame-only foreign host");
        helper.succeed();
    }

    private static ServerSubLevel ensureAndSeedMini(
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
        return child;
    }

    private static ServerSubLevel assembleFrameOnlyHost(
            ServerLevel level,
            BlockPos origin,
            List<BlockPos> frames) {
        BoundingBox3i bounds = BoundingBox3i.from(frames);
        check(bounds != null, "could not compute Frame-only Sable host bounds");
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                origin,
                frames,
                bounds.expand(1, 1, 1));
        check(host != null && !host.isRemoved(), "Sable did not create Frame-only foreign host");
        return host;
    }

    private static void hideAndEvaluate(
            ServerLevel level,
            MechanismAssemblyManager manager,
            MechanismAssembly assembly,
            BlockPos frame) {
        check(manager.setFrameShellMode(level, frame, FrameShellMode.HIDDEN),
                "could not request HIDDEN mode");
        HiddenFrameGeometryPolicy.request(level, assembly.id());
        HiddenFrameGeometryPolicy.tick(level);
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
