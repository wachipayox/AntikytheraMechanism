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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Cross-assembly regression for diagonal mini continuity inside one foreign host. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenFrameCrossAssemblyConnectivityGameTests {
    private HiddenFrameCrossAssemblyConnectivityGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void diagonalOtherAssemblyCanBridgeToHostAndRemovalExposesFrame(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceA = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos sourceB = sourceA.offset(1, 0, 1);
        BlockPos sourceSupport = sourceB.east();

        placeFrame(level, sourceA);
        placeFrame(level, sourceB);
        check(level.setBlock(sourceSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place external host support");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assemblyA = manager.getAssemblyAt(sourceA)
                .orElseThrow(() -> new AssertionError("missing source assembly A"));
        MechanismAssembly assemblyB = manager.getAssemblyAt(sourceB)
                .orElseThrow(() -> new AssertionError("missing source assembly B"));
        check(!assemblyA.id().equals(assemblyB.id()),
                "diagonal Frames unexpectedly merged before hosting");

        ServerSubLevel childA = MechanismSubLevelService.ensureForContent(level, assemblyA);
        ServerSubLevel childB = MechanismSubLevelService.ensureForContent(level, assemblyB);
        check(childA != null && childB != null, "could not stage both managed children");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                sourceA,
                List.of(sourceA, sourceB, sourceSupport),
                new BoundingBox3i(
                        sourceA.getX(), sourceA.getY(), sourceA.getZ(),
                        sourceSupport.getX(), sourceA.getY(), sourceB.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create shared foreign host");

        BlockPos hostedA = host.getPlot().getCenterBlock();
        BlockPos hostedB = hostedA.offset(1, 0, 1);
        BlockPos hostedSupport = hostedB.east();
        MechanismAssembly movedA = manager.getAssemblyAt(hostedA)
                .orElseThrow(() -> new AssertionError("assembly A did not follow host move"));
        MechanismAssembly movedB = manager.getAssemblyAt(hostedB)
                .orElseThrow(() -> new AssertionError("assembly B did not follow host move"));
        check(!movedA.id().equals(movedB.id()),
                "diagonal hosted Frames unexpectedly merged into one assembly");
        check(level.getBlockState(hostedSupport).is(Blocks.STONE),
                "host support did not move with the shared foreign host");

        ServerSubLevel movedChildA = MechanismSubLevelService.findExisting(level, movedA);
        ServerSubLevel movedChildB = MechanismSubLevelService.findExisting(level, movedB);
        check(movedChildA != null && movedChildB != null,
                "managed child disappeared during shared host move");

        // A has one mini at the macro-corner toward B. It does NOT directly touch hostedSupport.
        putMini(level, movedA, movedChildA, hostedA, 1, 0, 1, Blocks.OAK_PLANKS.defaultBlockState());

        // B supplies a two-cell bridge: first cell touches A only by macro-corner diagonal, second
        // touches the first by face and the host support by face. A can therefore be hidden only if
        // connectivity is evaluated in one physical host lattice across assembly boundaries.
        BlockPos bridgeGlobal = putMini(
                level, movedB, movedChildB, hostedB, 0, 0, 0, Blocks.OAK_PLANKS.defaultBlockState());
        putMini(level, movedB, movedChildB, hostedB, 1, 0, 0, Blocks.OAK_PLANKS.defaultBlockState());

        check(manager.setFrameShellMode(level, hostedA, FrameShellMode.HIDDEN),
                "could not request HIDDEN on assembly A");
        HiddenFrameGeometryPolicy.request(level, movedA.id());
        HiddenFrameGeometryPolicy.tick(level);
        assertMode(manager, level, movedA.id(), hostedA, FrameShellMode.HIDDEN,
                "cross-assembly diagonal bridge was not recognized");

        // Removing B's diagonal bridge cell must dirty hidden peer A even though the write belongs to
        // B's managed child. A can no longer reach the host and must permanently return to NORMAL.
        check(level.removeBlock(bridgeGlobal, false), "could not remove external diagonal mini bridge");
        HiddenFrameGeometryPolicy.tick(level);
        assertMode(manager, level, movedA.id(), hostedA, FrameShellMode.NORMAL,
                "removing another assembly's diagonal bridge did not expose assembly A");
        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static BlockPos putMini(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel child,
            BlockPos frame,
            int x,
            int y,
            int z,
            BlockState state) {
        BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
        check(level.setBlock(global, state, Block.UPDATE_ALL),
                "could not write mini cell " + mini + " for Frame " + frame);
        return global;
    }

    private static void assertMode(
            MechanismAssemblyManager manager,
            ServerLevel level,
            java.util.UUID assemblyId,
            BlockPos frame,
            FrameShellMode expected,
            String message) {
        MechanismAssembly assembly = manager.getAssembly(assemblyId)
                .orElseThrow(() -> new AssertionError("assembly disappeared"));
        check(assembly.shellMode() == expected,
                message + ": assembly mode=" + assembly.shellMode() + ", expected=" + expected);
        BlockState state = level.getBlockState(frame);
        check(state.is(ModRegistries.MECHANISM_FRAME.get()), message + ": physical Frame disappeared");
        check(state.getValue(MechanismFrameBlock.SHELL_MODE) == expected,
                message + ": Frame state=" + state.getValue(MechanismFrameBlock.SHELL_MODE)
                        + ", expected=" + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
