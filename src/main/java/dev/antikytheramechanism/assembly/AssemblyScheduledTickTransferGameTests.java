package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/** Regression coverage for queue-only mini payload transfers whose cells remain physically empty. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyScheduledTickTransferGameTests {
    private static final int LONG_DELAY = 200;

    private AssemblyScheduledTickTransferGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void scheduledBlockTickOnlyPayloadCreatesTargetAndMovesExactlyOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(2, 3, 2));
        placeFrame(level, frame);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(frame).orElseThrow();
        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, source);
        check(sourceChild != null && !sourceChild.isRemoved(), "could not materialize source child");
        BlockPos sourceMini = MiniCoordinateMapper.frameToMini(source, frame, 1, 0, 1);
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(sourceChild, sourceMini);
        assertAirOnly(level, sourceGlobal, "source block-tick cell");
        level.scheduleTick(sourceGlobal, Blocks.STONE, LONG_DELAY, TickPriority.HIGH);
        check(level.getBlockTicks().hasScheduledTick(sourceGlobal, Blocks.STONE), "block tick was not scheduled in source");

        MechanismAssembly target = new MechanismAssembly(UUID.randomUUID(), source.origin(), Set.of(frame), source.orientation());
        check(MechanismSubLevelService.findExisting(level, target) == null, "block-tick target unexpectedly started with a managed child");
        AssemblyContentTransferService.TransferResult result = AssemblyContentTransferService.transferFrames(level, source, target, Set.of(frame));
        check(result == AssemblyContentTransferService.TransferResult.SUCCESS, "scheduled-only block transfer did not report SUCCESS: " + result);

        ServerSubLevel targetChild = MechanismSubLevelService.findExisting(level, target);
        check(targetChild != null && !targetChild.isRemoved(), "scheduled-only block tick transfer did not materialize target child");
        BlockPos targetGlobal = MechanismSubLevelService.toPlotPosition(targetChild, MiniCoordinateMapper.frameToMini(target, frame, 1, 0, 1));
        assertAirOnly(level, targetGlobal, "target block-tick cell");
        check(!level.getBlockTicks().hasScheduledTick(sourceGlobal, Blocks.STONE), "source retained block tick after transfer");
        check(level.getBlockTicks().hasScheduledTick(targetGlobal, Blocks.STONE), "destination lost block tick during transfer");
        check(!MechanismSubLevelService.retireIfEmpty(level, target), "target child with a pending block tick was retired as physically empty");
        check(MechanismSubLevelService.findExisting(level, target) == targetChild, "target child identity changed while its block tick was pending");
        level.getBlockTicks().clearArea(BoundingBox.fromCorners(targetGlobal, targetGlobal));
        MechanismSubLevelService.remove(level, target);
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void scheduledFluidTickOnlyPayloadCreatesTargetAndMovesExactlyOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(5, 3, 2));
        placeFrame(level, frame);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly source = manager.getAssemblyAt(frame).orElseThrow();
        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, source);
        check(sourceChild != null && !sourceChild.isRemoved(), "could not materialize source child");
        BlockPos sourceMini = MiniCoordinateMapper.frameToMini(source, frame, 0, 1, 0);
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(sourceChild, sourceMini);
        assertAirOnly(level, sourceGlobal, "source fluid-tick cell");
        level.scheduleTick(sourceGlobal, Fluids.WATER, LONG_DELAY, TickPriority.HIGH);
        check(level.getFluidTicks().hasScheduledTick(sourceGlobal, Fluids.WATER), "fluid tick was not scheduled in source");

        MechanismAssembly target = new MechanismAssembly(UUID.randomUUID(), source.origin(), Set.of(frame), source.orientation());
        check(MechanismSubLevelService.findExisting(level, target) == null, "fluid-tick target unexpectedly started with a managed child");
        AssemblyContentTransferService.TransferResult result = AssemblyContentTransferService.transferFrames(level, source, target, Set.of(frame));
        check(result == AssemblyContentTransferService.TransferResult.SUCCESS, "scheduled-only fluid transfer did not report SUCCESS: " + result);

        ServerSubLevel targetChild = MechanismSubLevelService.findExisting(level, target);
        check(targetChild != null && !targetChild.isRemoved(), "scheduled-only fluid tick transfer did not materialize target child");
        BlockPos targetGlobal = MechanismSubLevelService.toPlotPosition(targetChild, MiniCoordinateMapper.frameToMini(target, frame, 0, 1, 0));
        assertAirOnly(level, targetGlobal, "target fluid-tick cell");
        check(!level.getFluidTicks().hasScheduledTick(sourceGlobal, Fluids.WATER), "source retained fluid tick after transfer");
        check(level.getFluidTicks().hasScheduledTick(targetGlobal, Fluids.WATER), "destination lost fluid tick during transfer");
        check(!MechanismSubLevelService.retireIfEmpty(level, target), "target child with a pending fluid tick was retired as physically empty");
        check(MechanismSubLevelService.findExisting(level, target) == targetChild, "target child identity changed while its fluid tick was pending");
        level.getFluidTicks().clearArea(BoundingBox.fromCorners(targetGlobal, targetGlobal));
        MechanismSubLevelService.remove(level, target);
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void scheduledBlockTickOnlyPayloadSurvivesOriginRebase(GameTestHelper helper) {
        verifyScheduledOnlyOriginRebase(helper, false);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void scheduledFluidTickOnlyPayloadSurvivesOriginRebase(GameTestHelper helper) {
        verifyScheduledOnlyOriginRebase(helper, true);
    }

    private static void verifyScheduledOnlyOriginRebase(GameTestHelper helper, boolean fluid) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(fluid ? 8 : 2, 3, 7));
        BlockPos retainedFrame = origin.east();
        placeFrame(level, origin);
        placeFrame(level, retainedFrame);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(origin).orElseThrow();
        UUID assemblyId = assembly.id();
        check(assembly.origin().equals(origin), "origin-rebase fixture did not start at endpoint origin");
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize rebase source child");
        UUID childId = child.getUniqueId();
        BlockPos oldGlobal = MechanismSubLevelService.toPlotPosition(child,
                MiniCoordinateMapper.frameToMini(assembly, retainedFrame, 1, 0, 1));
        assertAirOnly(level, oldGlobal, "pre-rebase scheduled-only cell");
        if (fluid) {
            level.scheduleTick(oldGlobal, Fluids.WATER, LONG_DELAY, TickPriority.HIGH);
            check(level.getFluidTicks().hasScheduledTick(oldGlobal, Fluids.WATER), "fluid tick was not scheduled before rebase");
        } else {
            level.scheduleTick(oldGlobal, Blocks.STONE, LONG_DELAY, TickPriority.HIGH);
            check(level.getBlockTicks().hasScheduledTick(oldGlobal, Blocks.STONE), "block tick was not scheduled before rebase");
        }

        check(level.destroyBlock(origin, false), "could not remove endpoint origin for scheduled-only rebase");
        MechanismAssembly rebased = manager.getAssemblyAt(retainedFrame).orElseThrow();
        check(rebased.id().equals(assemblyId), "origin rebase changed assembly UUID");
        check(rebased.origin().equals(retainedFrame) && rebased.frames().contains(rebased.origin()), "origin rebase left invalid origin");
        ServerSubLevel rebasedChild = MechanismSubLevelService.findExisting(level, rebased);
        check(rebasedChild != null && rebasedChild.getUniqueId().equals(childId), "origin rebase replaced original managed child");
        BlockPos newGlobal = MechanismSubLevelService.toPlotPosition(rebasedChild,
                MiniCoordinateMapper.frameToMini(rebased, retainedFrame, 1, 0, 1));
        check(!newGlobal.equals(oldGlobal), "origin rebase fixture did not change logical mini coordinate");
        assertAirOnly(level, newGlobal, "post-rebase scheduled-only cell");
        if (fluid) {
            check(!level.getFluidTicks().hasScheduledTick(oldGlobal, Fluids.WATER), "old logical coordinate retained fluid tick after rebase");
            check(level.getFluidTicks().hasScheduledTick(newGlobal, Fluids.WATER), "rebased logical coordinate lost fluid tick");
            level.getFluidTicks().clearArea(BoundingBox.fromCorners(newGlobal, newGlobal));
        } else {
            check(!level.getBlockTicks().hasScheduledTick(oldGlobal, Blocks.STONE), "old logical coordinate retained block tick after rebase");
            check(level.getBlockTicks().hasScheduledTick(newGlobal, Blocks.STONE), "rebased logical coordinate lost block tick");
            level.getBlockTicks().clearArea(BoundingBox.fromCorners(newGlobal, newGlobal));
        }
        long claims = manager.assemblies().stream().filter(candidate -> candidate.frames().contains(retainedFrame)).count();
        check(claims == 1, "origin rebase leaked a staging assembly claim");
        check(!manager.isContentRecoveryLocked(rebased.id()), "successful scheduled-only rebase left recovery lock");
        helper.succeed();
    }

    private static void assertAirOnly(ServerLevel level, BlockPos position, String label) {
        check(level.getBlockState(position).isAir(), label + " contains physical BlockState payload");
        check(level.getBlockEntity(position) == null, label + " contains a BlockEntity payload");
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
