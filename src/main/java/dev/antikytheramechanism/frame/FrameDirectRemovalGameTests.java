package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/** Regression coverage for command/programmatic Frame removal outside player destruction hooks. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameDirectRemovalGameTests {
    private FrameDirectRemovalGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void directAirReplacementDoesNotPoisonFrameCoordinate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState frameState = frameState();
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place original Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly original = manager.getAssemblyAt(framePos)
                .orElseThrow(() -> new AssertionError("original Frame was not indexed"));
        UUID originalId = original.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, original);
        check(child != null && !child.isRemoved(), "could not materialize mini content before direct removal");
        seedPayload(child, original, framePos, 0, 0, 0, Blocks.STONE);
        manager.refreshFrame(level, framePos);

        // This is the same world mutation used by /setblock ... air, deliberately bypassing the
        // playerWillDestroy pre-evacuation path. It must return without recursively mutating Sable.
        check(level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                "direct AIR replacement failed");
        check(level.getBlockState(framePos).isAir(), "Frame survived direct AIR replacement");

        helper.runAfterDelay(3, () -> {
            check(manager.getAssemblyAt(framePos).isEmpty(),
                    "deferred direct removal left stale Frame ownership at the coordinate");
            check(manager.getAssembly(originalId).isEmpty(),
                    "single-Frame assembly survived deferred direct removal");
            check(!manager.isContentRecoveryLocked(originalId),
                    "successful direct removal left a recovery lock");
            check(child.isRemoved(), "single-Frame managed child survived direct removal");

            // Reusing the exact coordinate is the important poisoned-position regression. The new
            // Frame must receive a fresh owner and remain normally removable afterwards.
            check(level.setBlock(framePos, frameState, Block.UPDATE_ALL),
                    "could not place replacement Frame at directly-cleared coordinate");
            helper.runAfterDelay(2, () -> {
                MechanismAssembly replacement = manager.getAssemblyAt(framePos)
                        .orElseThrow(() -> new AssertionError("replacement Frame was not indexed"));
                check(!replacement.id().equals(originalId),
                        "replacement Frame inherited stale assembly identity from removed Frame");
                assertFrameEntity(level, manager, framePos);

                check(level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                        "replacement Frame could not be removed a second time");
                helper.runAfterDelay(3, () -> {
                    check(level.getBlockState(framePos).isAir(),
                            "replacement Frame survived second direct removal");
                    check(manager.getAssemblyAt(framePos).isEmpty(),
                            "second direct removal left poisoned ownership behind");
                    check(!manager.isContentRecoveryLocked(replacement.id()),
                            "second direct removal left replacement recovery-locked");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void repeatedDirectAirCyclesKeepCoordinateReusable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(9, 3, 4));
        repeatDirectAirCycle(helper, level, MechanismAssemblyManager.get(level), framePos, null, 0);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void directAirOriginBridgeSplitsCleanlyAndCanBeReplaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bridge = helper.absolutePos(new BlockPos(5, 3, 10));
        BlockPos left = bridge.west();
        BlockPos right = bridge.east();
        BlockState frameState = frameState();
        check(level.setBlock(bridge, frameState, Block.UPDATE_ALL), "could not place bridge origin");
        check(level.setBlock(left, frameState, Block.UPDATE_ALL), "could not place left Frame");
        check(level.setBlock(right, frameState, Block.UPDATE_ALL), "could not place right Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(bridge).orElseThrow();
        check(joined.origin().equals(bridge), "bridge fixture did not keep bridge as semantic origin");
        check(joined.frames().equals(Set.of(left, bridge, right)), "bridge fixture did not form one assembly");
        UUID joinedId = joined.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, joined);
        check(child != null && !child.isRemoved(), "could not materialize bridge fixture child");
        seedPayload(child, joined, left, 0, 0, 0, Blocks.GOLD_BLOCK);
        seedPayload(child, joined, bridge, 1, 0, 0, Blocks.STONE);
        seedPayload(child, joined, right, 1, 1, 1, Blocks.DIAMOND_BLOCK);
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, left);
        manager.refreshFrame(level, bridge);
        manager.refreshFrame(level, right);

        check(level.setBlock(bridge, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                "could not directly clear origin bridge");
        check(level.getBlockState(bridge).isAir(), "origin bridge survived direct AIR write");

        helper.runAfterDelay(4, () -> {
            check(manager.getAssemblyAt(bridge).isEmpty(), "removed bridge retained frameIndex ownership");
            MechanismAssembly leftAssembly = manager.getAssemblyAt(left)
                    .orElseThrow(() -> new AssertionError("left component lost ownership"));
            MechanismAssembly rightAssembly = manager.getAssemblyAt(right)
                    .orElseThrow(() -> new AssertionError("right component lost ownership"));
            check(!leftAssembly.id().equals(rightAssembly.id()), "bridge removal did not split components");
            check(leftAssembly.frames().equals(Set.of(left)) && leftAssembly.origin().equals(left),
                    "left singleton has invalid origin after direct bridge removal");
            check(rightAssembly.frames().equals(Set.of(right)) && rightAssembly.origin().equals(right),
                    "right singleton has invalid origin after direct bridge removal");
            check(!manager.isContentRecoveryLocked(joinedId)
                            && !manager.isContentRecoveryLocked(leftAssembly.id())
                            && !manager.isContentRecoveryLocked(rightAssembly.id()),
                    "successful direct bridge removal left recovery lock");
            assertPayload(level, leftAssembly, left, 0, 0, 0, Blocks.GOLD_BLOCK);
            assertPayload(level, rightAssembly, right, 1, 1, 1, Blocks.DIAMOND_BLOCK);

            check(level.setBlock(bridge, frameState, Block.UPDATE_ALL),
                    "could not replace cleared origin bridge");
            helper.runAfterDelay(3, () -> {
                assertFrameEntity(level, manager, bridge);
                check(level.setBlock(bridge, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                        "replacement articulation Frame could not be cleared again");
                helper.runAfterDelay(4, () -> {
                    check(level.getBlockState(bridge).isAir(),
                            "replacement articulation Frame survived second direct clear");
                    check(manager.getAssemblyAt(bridge).isEmpty(),
                            "replacement articulation Frame left stale ownership");
                    check(manager.getAssemblyAt(left).isPresent() && manager.getAssemblyAt(right).isPresent(),
                            "second bridge removal lost surviving components");
                    helper.succeed();
                });
            });
        });
    }

    private static void repeatDirectAirCycle(
            GameTestHelper helper,
            ServerLevel level,
            MechanismAssemblyManager manager,
            BlockPos framePos,
            UUID previousId,
            int cycle) {
        check(level.setBlock(framePos, frameState(), Block.UPDATE_ALL),
                "could not place Frame for direct AIR cycle " + cycle);
        helper.runAfterDelay(2, () -> {
            MechanismAssembly assembly = manager.getAssemblyAt(framePos)
                    .orElseThrow(() -> new AssertionError("cycle " + cycle + " Frame was not indexed"));
            if (previousId != null) {
                check(!previousId.equals(assembly.id()),
                        "cycle " + cycle + " reused stale assembly UUID");
            }
            UUID assemblyId = assembly.id();
            ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
            check(child != null && !child.isRemoved(), "cycle " + cycle + " could not materialize child");
            seedPayload(child, assembly, framePos, cycle & 1, 0, (cycle >>> 1) & 1, Blocks.STONE);
            child.getPlot().updateBoundingBox();
            manager.refreshFrame(level, framePos);
            check(level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                    "cycle " + cycle + " direct AIR failed");
            helper.runAfterDelay(3, () -> {
                check(level.getBlockState(framePos).isAir(), "cycle " + cycle + " Frame survived removal");
                check(manager.getAssemblyAt(framePos).isEmpty(), "cycle " + cycle + " left stale frameIndex");
                check(manager.getAssembly(assemblyId).isEmpty(), "cycle " + cycle + " left stale assembly");
                check(!manager.isContentRecoveryLocked(assemblyId), "cycle " + cycle + " left recovery lock");
                check(child.isRemoved(), "cycle " + cycle + " left managed child alive");
                if (cycle >= 2) {
                    helper.succeed();
                } else {
                    repeatDirectAirCycle(helper, level, manager, framePos, assemblyId, cycle + 1);
                }
            });
        });
    }

    private static void seedPayload(
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos framePos,
            int x,
            int y,
            int z,
            Block block) {
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        miniLocal, block.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed " + block + " in " + framePos);
    }

    private static void assertPayload(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePos,
            int x,
            int y,
            int z,
            Block expected) {
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        check(child != null && !child.isRemoved(), "assembly lost managed child for payload assertion");
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        check(level.getBlockState(global).is(expected),
                "payload mismatch at " + framePos + ": expected " + expected + " but found " + level.getBlockState(global));
    }

    private static void assertFrameEntity(
            ServerLevel level,
            MechanismAssemblyManager manager,
            BlockPos framePos) {
        MechanismAssembly assembly = manager.getAssemblyAt(framePos)
                .orElseThrow(() -> new AssertionError("Frame is not indexed at " + framePos));
        BlockEntity blockEntity = level.getBlockEntity(framePos);
        check(blockEntity instanceof MechanismFrameBlockEntity,
                "Frame has no MechanismFrameBlockEntity at " + framePos);
        MechanismFrameBlockEntity frameEntity = (MechanismFrameBlockEntity) blockEntity;
        check(assembly.id().equals(frameEntity.getAssemblyId()),
                "Frame block entity ownership disagrees with manager at " + framePos);
    }

    private static BlockState frameState() {
        return ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
