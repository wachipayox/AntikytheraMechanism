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
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;

/** Regression coverage for hosted HIDDEN Frames becoming persistently visible when geometry needs them. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenFrameGeometryPolicyGameTests {
    private HiddenFrameGeometryPolicyGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void emptyMemberForcesWholeHostedAssemblyVisibleAndDoesNotAutoHide(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 2, Blocks.STONE.defaultBlockState());
        BlockPos first = setup.frames().get(0);
        BlockPos second = setup.frames().get(1);

        putMini(setup, first, 0, 0, 0, Blocks.STONE.defaultBlockState());
        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.NORMAL,
                "assembly with an empty member Frame stayed hidden");

        // Repair every condition after the automatic reset. NORMAL must remain persistent until the
        // player explicitly requests HIDDEN again; geometry recovery is never an automatic hide.
        putMini(setup, first, 1, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 0, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 1, 0, 0, Blocks.STONE.defaultBlockState());
        HiddenFrameGeometryPolicy.request(setup.level(), setup.assembly().id());
        HiddenFrameGeometryPolicy.tick(setup.level());
        assertMode(setup, FrameShellMode.NORMAL,
                "assembly hid itself again after valid geometry was restored");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void disconnectedMiniIslandsForceHostedFrameVisible(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 1, Blocks.STONE.defaultBlockState());
        BlockPos frame = setup.frames().getFirst();

        putMini(setup, frame, 0, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, frame, 1, 1, 1, Blocks.IRON_BLOCK.defaultBlockState());
        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.NORMAL,
                "two face-disconnected mini islands stayed hidden");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void faceConnectedMiniBridgeAcrossFramesCanRemainHidden(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 2, Blocks.STONE.defaultBlockState());
        BlockPos first = setup.frames().get(0);
        BlockPos second = setup.frames().get(1);

        // Four cells make one exact six-neighbour chain across the Frame boundary. This is the same
        // spatial neighbourhood Create considers before its glue/sticky predicates are evaluated.
        putMini(setup, first, 0, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, first, 1, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 0, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 1, 0, 0, Blocks.STONE.defaultBlockState());

        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.HIDDEN,
                "face-connected hosted payload was incorrectly forced visible");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void visibleGapBetweenMiniAndHostForcesFrameVisible(GameTestHelper helper) {
        // A bottom slab one macro block below the Frame is in the same foreign host but its collision
        // shape ends at y=.5. It therefore leaves a visible .5-block gap before the Frame's bottom
        // face and must not count as structural support.
        HostedSetup setup = createHostedSetup(helper, 1, Blocks.OAK_SLAB.defaultBlockState());
        BlockPos frame = setup.frames().getFirst();
        putMini(setup, frame, 0, 0, 0, Blocks.STONE.defaultBlockState());

        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.NORMAL,
                "host block that did not reach the shared face incorrectly anchored hidden minis");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void diagonalForeignHostPoseStillRecognizesLocalFaceSupport(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 1, Blocks.STONE.defaultBlockState());
        BlockPos frame = setup.frames().getFirst();
        putMini(setup, frame, 0, 0, 0, Blocks.STONE.defaultBlockState());

        // Host-local Frame/miniblock adjacency must be invariant under arbitrary world rotation.
        // Rotate the entire foreign body 45 degrees: in world axes its support is now diagonal, while
        // the host's own logical grid still has an exact shared face.
        setup.host().logicalPose().orientation().set(new Quaterniond().rotateY(Math.PI / 4.0));
        setup.host().updateBoundingBox();
        setup.host().updateLastPose();

        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.HIDDEN,
                "diagonally rotated foreign host lost valid local Frame support");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void removingHostSupportAutomaticallyQueuesVisibilityReset(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 1, Blocks.STONE.defaultBlockState());
        BlockPos frame = setup.frames().getFirst();
        putMini(setup, frame, 0, 0, 0, Blocks.STONE.defaultBlockState());

        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.HIDDEN, "valid hosted Frame could not start hidden");

        // Do not explicitly request the second evaluation. LevelChunkFrameMaskMixin must recognize
        // this concrete foreign-host geometry write and queue the affected neighboring assembly.
        check(setup.level().removeBlock(frame.below(), false), "could not remove host support block");
        HiddenFrameGeometryPolicy.tick(setup.level());
        assertMode(setup, FrameShellMode.NORMAL,
                "removing the only host support did not automatically expose the Frame");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void breakingMiniBridgeAutomaticallyQueuesVisibilityReset(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper, 2, Blocks.STONE.defaultBlockState());
        BlockPos first = setup.frames().get(0);
        BlockPos second = setup.frames().get(1);

        putMini(setup, first, 0, 0, 0, Blocks.STONE.defaultBlockState());
        BlockPos bridge = putMini(setup, first, 1, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 0, 0, 0, Blocks.STONE.defaultBlockState());
        putMini(setup, second, 1, 0, 0, Blocks.STONE.defaultBlockState());
        hideAndEvaluate(setup);
        assertMode(setup, FrameShellMode.HIDDEN, "connected hosted bridge could not start hidden");

        // Again rely exclusively on the write hook. Both Frames remain non-empty after this removal,
        // so the expected reset specifically proves the disconnected-component rule.
        check(setup.level().removeBlock(bridge, false), "could not remove mini bridge cell");
        HiddenFrameGeometryPolicy.tick(setup.level());
        assertMode(setup, FrameShellMode.NORMAL,
                "breaking mini face-connectivity did not automatically expose the assembly");
        helper.succeed();
    }

    private static HostedSetup createHostedSetup(
            GameTestHelper helper,
            int frameCount,
            BlockState supportState) {
        check(frameCount >= 1, "test requires at least one Frame");
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos support = rootFrame.below();
        check(level.setBlock(support, supportState, Block.UPDATE_ALL),
                "could not place foreign-host support block");

        List<BlockPos> sourceFrames = new ArrayList<>();
        for (int index = 0; index < frameCount; index++) {
            BlockPos frame = rootFrame.east(index);
            placeFrame(level, frame);
            sourceFrames.add(frame);
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(rootFrame)
                .orElseThrow(() -> new AssertionError("missing source Frame assembly"));
        check(source.frames().size() == frameCount,
                "source Frames did not form one assembly before hosting");
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, source);
        check(child != null && !child.isRemoved(), "could not stage managed mini child");

        List<BlockPos> movedBlocks = new ArrayList<>(sourceFrames);
        movedBlocks.add(support);
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                movedBlocks,
                new BoundingBox3i(
                        rootFrame.getX(), support.getY(), rootFrame.getZ(),
                        rootFrame.getX() + frameCount - 1, rootFrame.getY(), rootFrame.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create foreign host");

        BlockPos hostedRoot = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(hostedRoot)
                .orElseThrow(() -> new AssertionError("assembly did not follow Sable host move"));
        check(moved.id().equals(source.id()), "Sable host move changed assembly UUID");
        ServerSubLevel movedChild = MechanismSubLevelService.findExisting(level, moved);
        check(movedChild != null && !movedChild.isRemoved(), "managed child disappeared during host move");

        List<BlockPos> hostedFrames = new ArrayList<>();
        for (int index = 0; index < frameCount; index++) {
            BlockPos frame = hostedRoot.east(index);
            check(manager.getAssemblyAt(frame)
                            .map(MechanismAssembly::id)
                            .filter(moved.id()::equals)
                            .isPresent(),
                    "hosted Frame mapping is incomplete at " + frame);
            hostedFrames.add(frame);
        }
        check(level.getBlockState(hostedRoot.below()).equals(supportState),
                "support block did not move into the foreign host beside the Frame");
        return new HostedSetup(level, manager, moved, movedChild, host, List.copyOf(hostedFrames));
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    /** @return concrete global plot position of the written mini cell. */
    private static BlockPos putMini(
            HostedSetup setup,
            BlockPos frame,
            int x,
            int y,
            int z,
            BlockState state) {
        BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(
                setup.assembly(), frame, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(setup.child(), mini);
        check(setup.level().setBlock(global, state, Block.UPDATE_ALL),
                "could not write mini cell " + mini + " for Frame " + frame);
        return global;
    }

    private static void hideAndEvaluate(HostedSetup setup) {
        check(setup.manager().setFrameShellMode(
                        setup.level(), setup.frames().getFirst(), FrameShellMode.HIDDEN),
                "could not request HIDDEN Frame mode");
        HiddenFrameGeometryPolicy.request(setup.level(), setup.assembly().id());
        HiddenFrameGeometryPolicy.tick(setup.level());
    }

    private static void assertMode(HostedSetup setup, FrameShellMode expected, String message) {
        MechanismAssembly current = setup.manager().getAssembly(setup.assembly().id())
                .orElseThrow(() -> new AssertionError("hosted assembly disappeared"));
        check(current.shellMode() == expected,
                message + ": assembly mode=" + current.shellMode() + ", expected=" + expected);
        for (BlockPos frame : setup.frames()) {
            BlockState state = setup.level().getBlockState(frame);
            check(state.is(ModRegistries.MECHANISM_FRAME.get()),
                    message + ": physical Frame disappeared at " + frame);
            check(state.getValue(MechanismFrameBlock.SHELL_MODE) == expected,
                    message + ": Frame " + frame + " state="
                            + state.getValue(MechanismFrameBlock.SHELL_MODE) + ", expected=" + expected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record HostedSetup(
            ServerLevel level,
            MechanismAssemblyManager manager,
            MechanismAssembly assembly,
            ServerSubLevel child,
            ServerSubLevel host,
            List<BlockPos> frames) {
    }
}
