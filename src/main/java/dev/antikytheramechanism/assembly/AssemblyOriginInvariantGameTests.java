package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
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

import java.util.Set;
import java.util.UUID;

/** Regression coverage for the invariant that every live assembly origin is one of its Frames. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyOriginInvariantGameTests {
    private AssemblyOriginInvariantGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void removingOriginBridgeRebasesBothSingletonComponentsAndPayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos middle = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos left = middle.west();
        BlockPos right = middle.east();

        // Place the bridge first so it is intentionally the semantic origin of the joined assembly.
        placeFrame(level, middle);
        placeFrame(level, left);
        placeFrame(level, right);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(middle).orElseThrow();
        UUID originalId = joined.id();
        check(joined.origin().equals(middle), "bridge Frame did not become the test assembly origin");
        check(joined.frames().equals(Set.of(left, middle, right)), "three Frames did not join before origin removal");

        ServerSubLevel joinedChild = MechanismSubLevelService.ensureForContent(level, joined);
        check(joinedChild != null && !joinedChild.isRemoved(), "could not materialize joined mini payload");
        UUID originalChildId = joinedChild.getUniqueId();
        BlockPos oldLeftGlobal = seedPayload(level, joinedChild, joined, left, 0, 0, 0, Blocks.GOLD_BLOCK);
        seedPayload(level, joinedChild, joined, right, 1, 1, 1, Blocks.DIAMOND_BLOCK);

        check(level.destroyBlock(middle, false), "could not remove origin bridge through the real block lifecycle");

        MechanismAssembly leftAssembly = manager.getAssemblyAt(left).orElseThrow();
        MechanismAssembly rightAssembly = manager.getAssemblyAt(right).orElseThrow();
        check(!leftAssembly.id().equals(rightAssembly.id()), "removing bridge origin did not split the assembly");
        check(originalId.equals(leftAssembly.id()) || originalId.equals(rightAssembly.id()),
                "original assembly UUID did not survive on either retained component");
        assertSingletonOrigin(leftAssembly, left);
        assertSingletonOrigin(rightAssembly, right);
        assertFrameMapping(level, leftAssembly, left);
        assertFrameMapping(level, rightAssembly, right);
        assertPayload(level, leftAssembly, left, 0, 0, 0, Blocks.GOLD_BLOCK);
        assertPayload(level, rightAssembly, right, 1, 1, 1, Blocks.DIAMOND_BLOCK);
        check(manager.assemblies().size() == 2,
                "origin rebase leaked a staging assembly after bridge split");
        MechanismAssembly retained = originalId.equals(leftAssembly.id()) ? leftAssembly : rightAssembly;
        assertOriginalChildPreserved(level, retained, originalChildId);
        check(!manager.isContentRecoveryLocked(leftAssembly.id())
                        && !manager.isContentRecoveryLocked(rightAssembly.id()),
                "successful origin bridge split left a component recovery-locked");

        // The original retained child's old logical coordinate must have been evacuated during the
        // origin rebase; otherwise the payload was copied rather than moved transactionally.
        if (originalId.equals(leftAssembly.id())) {
            check(level.getBlockState(oldLeftGlobal).isAir(),
                    "old pre-rebase mini coordinate still contains duplicated retained payload");
        }
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void removingEndpointOriginRebasesStillConnectedAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(3, 3, 8));
        BlockPos second = first.east();
        BlockPos third = second.east();
        placeFrame(level, first);
        placeFrame(level, second);
        placeFrame(level, third);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(first).orElseThrow();
        UUID originalId = joined.id();
        check(joined.origin().equals(first), "endpoint did not become assembly origin");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, joined);
        check(child != null && !child.isRemoved(), "could not materialize connected-remainder payload");
        UUID originalChildId = child.getUniqueId();
        seedPayload(level, child, joined, second, 0, 1, 0, Blocks.IRON_BLOCK);
        seedPayload(level, child, joined, third, 1, 0, 1, Blocks.EMERALD_BLOCK);

        check(level.destroyBlock(first, false), "could not remove endpoint origin");

        MechanismAssembly remaining = manager.getAssemblyAt(second).orElseThrow();
        check(remaining.id().equals(originalId), "connected remainder unexpectedly changed assembly UUID");
        check(remaining.frames().equals(Set.of(second, third)), "connected remainder lost a Frame");
        check(remaining.origin().equals(second), "connected remainder did not choose deterministic surviving origin");
        check(remaining.frames().contains(remaining.origin()), "connected remainder retained a stale origin");
        assertFrameMapping(level, remaining, second);
        assertFrameMapping(level, remaining, third);
        assertPayload(level, remaining, second, 0, 1, 0, Blocks.IRON_BLOCK);
        assertPayload(level, remaining, third, 1, 0, 1, Blocks.EMERALD_BLOCK);
        check(manager.assemblies().size() == 1,
                "connected origin rebase leaked a staging assembly");
        assertOriginalChildPreserved(level, remaining, originalChildId);
        check(!manager.isContentRecoveryLocked(remaining.id()),
                "successful connected origin rebase left its assembly recovery-locked");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void splitRetainedBySizeCannotLeaveSurvivingOriginOutsideOriginalAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(2, 3, 12));
        BlockPos bridge = first.east();
        BlockPos third = bridge.east();
        BlockPos fourth = third.east();
        BlockPos fifth = fourth.east();
        placeFrame(level, first);
        placeFrame(level, bridge);
        placeFrame(level, third);
        placeFrame(level, fourth);
        placeFrame(level, fifth);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(first).orElseThrow();
        UUID originalId = joined.id();
        check(joined.origin().equals(first), "surviving-origin fixture did not start at first Frame");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, joined);
        check(child != null && !child.isRemoved(), "could not materialize surviving-origin payload");
        UUID originalChildId = child.getUniqueId();
        seedPayload(level, child, joined, first, 1, 0, 0, Blocks.COPPER_BLOCK);
        seedPayload(level, child, joined, fifth, 0, 1, 1, Blocks.LAPIS_BLOCK);

        // FrameGraph retains the larger third/fourth/fifth component. Before the invariant repair this
        // left the original UUID there while its semantic origin still pointed at the surviving first
        // Frame, which had already been transferred to the small split assembly.
        check(level.destroyBlock(bridge, false), "could not remove asymmetric bridge");

        MechanismAssembly small = manager.getAssemblyAt(first).orElseThrow();
        MechanismAssembly large = manager.getAssemblyAt(third).orElseThrow();
        check(!small.id().equals(large.id()), "asymmetric bridge removal did not split the graph");
        check(large.id().equals(originalId), "largest retained component no longer keeps the original UUID");
        check(small.origin().equals(first) && small.frames().contains(small.origin()),
                "small component has an invalid origin");
        check(large.origin().equals(third) && large.frames().contains(large.origin()),
                "large retained component was not rebased away from the transferred old origin");
        assertFrameMapping(level, small, first);
        assertFrameMapping(level, large, third);
        assertFrameMapping(level, large, fourth);
        assertFrameMapping(level, large, fifth);
        assertPayload(level, small, first, 1, 0, 0, Blocks.COPPER_BLOCK);
        assertPayload(level, large, fifth, 0, 1, 1, Blocks.LAPIS_BLOCK);
        check(manager.assemblies().size() == 2,
                "asymmetric split leaked a staging assembly");
        assertOriginalChildPreserved(level, large, originalChildId);
        check(!manager.isContentRecoveryLocked(small.id())
                        && !manager.isContentRecoveryLocked(large.id()),
                "successful asymmetric split left a component recovery-locked");
        helper.succeed();
    }

    private static BlockPos seedPayload(
            ServerLevel level,
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos frame,
            int x,
            int y,
            int z,
            Block block) {
        BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(global, block.defaultBlockState(), Block.UPDATE_ALL)),
                "could not seed " + block + " in Frame " + frame);
        return global;
    }

    private static void assertPayload(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos frame,
            int x,
            int y,
            int z,
            Block expected) {
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        check(child != null && !child.isRemoved(),
                "assembly " + assembly.id() + " lost its managed child");
        BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
        check(level.getBlockState(global).is(expected),
                "payload mismatch at " + frame + " cell " + new BlockPos(x, y, z)
                        + ": expected " + expected + " but found " + level.getBlockState(global));
    }

    private static void assertOriginalChildPreserved(
            ServerLevel level,
            MechanismAssembly retained,
            UUID originalChildId) {
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, retained);
        check(child != null && !child.isRemoved(),
                "retained assembly lost its original managed child during origin rebase");
        check(originalChildId.equals(child.getUniqueId()),
                "origin rebase replaced the original managed child instead of rebasing its payload");
    }

    private static void assertSingletonOrigin(MechanismAssembly assembly, BlockPos frame) {
        check(assembly.frames().equals(Set.of(frame)), "expected singleton component at " + frame);
        check(assembly.origin().equals(frame), "singleton origin is not its only Frame at " + frame);
        check(assembly.frames().contains(assembly.origin()), "singleton retained stale origin at " + frame);
    }

    private static void assertFrameMapping(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePosition) {
        check(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity,
                "missing Mechanism Frame BlockEntity at " + framePosition);
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePosition);
        check(assembly.id().equals(frame.getAssemblyId()),
                "Frame retained stale assembly UUID at " + framePosition);
        check(assembly.logicalFrameOffset(framePosition).equals(frame.getLogicalFrameOffset()),
                "Frame retained stale logical offset at " + framePosition);
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
