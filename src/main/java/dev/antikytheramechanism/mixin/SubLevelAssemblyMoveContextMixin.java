package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.List;

/** Gives Antikythera visibility of the complete synchronous Sable relocation operation. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SubLevelAssemblyMoveContextMixin {

    @WrapMethod(method = "assembleBlocks")
    private static ServerSubLevel antikytheramechanism$propagateDetachedMiniIdentity(
            ServerLevel level,
            BlockPos anchor,
            Iterable<BlockPos> blocks,
            BoundingBox3ic bounds,
            Operation<ServerSubLevel> original) {
        List<BlockPos> frozenBlocks = new ArrayList<>();
        for (BlockPos block : blocks) {
            frozenBlocks.add(block.immutable());
        }

        boolean miniPhysicsAssembly = MiniPhysicsAssemblyContext.isActive();
        if (miniPhysicsAssembly) {
            for (BlockPos block : frozenBlocks) {
                BlockState state = level.getBlockState(block);
                if (!MiniPhysicsAssemblyContext.allowsCandidate(level, block, state)) {
                    AntikytheraMechanism.LOGGER.warn(
                            "Rejected mini Physics Assembler result before Sable move: final block {} ({}) escaped the exact source 0.5 SubLevel",
                            block,
                            state.getBlock());
                    return null;
                }
            }
        }

        SubLevel anchorSource = Sable.HELPER.getContaining(level, anchor);
        boolean detachedSelected = DetachedMiniPhysicsSubLevelService.isDetached(anchorSource);
        if (!detachedSelected) {
            for (BlockPos block : frozenBlocks) {
                if (DetachedMiniPhysicsSubLevelService.isDetached(Sable.HELPER.getContaining(level, block))) {
                    detachedSelected = true;
                    break;
                }
            }
        }

        if (detachedSelected) {
            for (BlockPos block : frozenBlocks) {
                SubLevel containing = Sable.HELPER.getContaining(level, block);
                if (containing == null || !DetachedMiniPhysicsSubLevelService.hasHalfScale(containing)) {
                    AntikytheraMechanism.LOGGER.warn(
                            "Rejected Sable mixed-scale assembly before move: detached Antikythera 0.5 content was selected with incompatible block {} in {}",
                            block,
                            containing == null ? "root world" : "SubLevel " + containing.getUniqueId()
                                    + " scale=" + containing.logicalPose().scale());
                    return null;
                }
            }
        }

        boolean createsDetachedBody = miniPhysicsAssembly || detachedSelected;
        ServerSubLevel result = createsDetachedBody
                ? DetachedMiniPhysicsSubLevelService.duringDetachedCreation(
                        () -> original.call(level, anchor, frozenBlocks, bounds))
                : original.call(level, anchor, frozenBlocks, bounds);
        if (detachedSelected && result != null && !result.isRemoved()) {
            DetachedMiniPhysicsSubLevelService.markDetached(result);
        }
        return result;
    }

    @WrapMethod(method = "moveBlocks")
    private static void antikythera$withCompleteMoveContext(
            ServerLevel sourceLevel,
            AssemblyTransform transform,
            Iterable<BlockPos> blocks,
            Operation<Void> original) {
        List<BlockPos> movedBlocks = new ArrayList<>();
        for (BlockPos block : blocks) {
            movedBlocks.add(block.immutable());
        }

        SableAssemblyMoveContext.begin(sourceLevel, transform, movedBlocks);
        boolean completed = false;
        try {
            antikythera$stabilizeManagedChildReadPath(sourceLevel, transform, movedBlocks);

            if (!SableFrameRelocationService.prepareMoveOperation(sourceLevel, movedBlocks)) {
                throw new IllegalStateException(
                        "Antikythera could not safely partition a partial Frame assembly for Sable moveBlocks");
            }

            original.call(sourceLevel, transform, movedBlocks);
            completed = true;
        } finally {
            try {
                if (completed) {
                    SableFrameRelocationService.finishMoveOperation(sourceLevel);
                }
            } finally {
                SableAssemblyMoveContext.end();
            }
        }
    }

    private static void antikythera$stabilizeManagedChildReadPath(
            ServerLevel level,
            AssemblyTransform transform,
            List<BlockPos> movedBlocks) {
        if (movedBlocks.isEmpty()) {
            return;
        }

        SubLevel rawSource = Sable.HELPER.getContaining(level, movedBlocks.getFirst());
        SubLevel rawTarget = Sable.HELPER.getContaining(level, transform.apply(movedBlocks.getFirst()));
        if (!(rawSource instanceof ServerSubLevel source)
                || !(rawTarget instanceof ServerSubLevel target)
                || source == target
                || MechanismSubLevelService.getOwnerAssemblyId(source) == null
                || MechanismSubLevelService.getOwnerAssemblyId(target) == null) {
            return;
        }

        List<BlockState> expected = movedBlocks.stream().map(level::getBlockState).toList();
        List<Integer> mismatches = antikythera$acceleratedMismatches(level, movedBlocks, expected);
        if (mismatches.isEmpty()) {
            return;
        }

        List<Integer> retryMismatches = antikythera$acceleratedMismatches(level, movedBlocks, expected);
        if (retryMismatches.isEmpty()) {
            AntikytheraMechanism.LOGGER.debug(
                    "Stabilized Sable accelerated reads for managed child transfer {} -> {} after one warm-up pass",
                    source.getUniqueId(), target.getUniqueId());
            return;
        }

        int first = retryMismatches.getFirst();
        BlockPos position = movedBlocks.get(first);
        BlockState regular = expected.get(first);
        BlockState accelerated = new LevelAccelerator(level).getBlockState(position);
        throw new IllegalStateException(
                "Sable LevelAccelerator disagrees with managed child source snapshot at "
                        + position
                        + " (regular=" + regular
                        + ", accelerated=" + accelerated
                        + ", sourceChild=" + source.getUniqueId()
                        + ", targetChild=" + target.getUniqueId() + ")");
    }

    private static List<Integer> antikythera$acceleratedMismatches(
            ServerLevel level,
            List<BlockPos> positions,
            List<BlockState> expected) {
        LevelAccelerator accelerator = new LevelAccelerator(level);
        List<Integer> mismatches = new ArrayList<>();
        for (int index = 0; index < positions.size(); index++) {
            if (!accelerator.getBlockState(positions.get(index)).equals(expected.get(index))) {
                mismatches.add(index);
            }
        }
        return mismatches;
    }
}
