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

    /**
     * Sable's heat-map splitter also creates bodies through assembleBlocks. Preserve the detached
     * Antikythera subtype when an already-free 0.5 body splits, while deliberately doing nothing for
     * Frame-owned children (the initial Physics Assembler ejection is marked by its Simulated hook).
     *
     * <p>This is also the final immutable boundary before Sable moves blocks. If any selected block
     * belongs to a detached Antikythera body, every selected block must belong to a real Sable
     * SubLevel at scale 0.5. Root/macro positions and differently-scaled bodies are rejected before
     * {@code original.call}, so Sable cannot enter an invalid mixed-scale transform. Normal Sable
     * bodies that are themselves 0.5 are intentionally allowed.</p>
     *
     * <p>When Simulated is assembling out of a Frame, the older exact-source rule remains stricter:
     * every final position must still belong to that one managed Frame child.</p>
     */
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

        if (MiniPhysicsAssemblyContext.isActive()) {
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

        ServerSubLevel result = original.call(level, anchor, frozenBlocks, bounds);
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
        // moveBlocks itself iterates this collection several times. Materialize it once before the
        // Antikythera pre-pass so our context never consumes a caller-supplied lazy iterable before
        // Sable sees it, and freeze positions against mutable BlockPos implementations.
        List<BlockPos> movedBlocks = new ArrayList<>();
        for (BlockPos block : blocks) {
            movedBlocks.add(block.immutable());
        }

        // Begin before the accelerated-read preflight so the same LevelAccelerator routing used by
        // the real Sable move is already active while Antikythera verifies that path.
        SableAssemblyMoveContext.begin(sourceLevel, transform, movedBlocks);
        boolean completed = false;
        try {
            // Antikythera's split/merge transfer snapshots use normal ServerLevel reads, whereas
            // Sable's moveBlocks deliberately uses LevelAccelerator. Keep the fail-closed comparison
            // for managed-child transfers under the exact routed accelerator context used below.
            antikythera$stabilizeManagedChildReadPath(sourceLevel, transform, movedBlocks);

            // A Sable host split can move only a strict subset of the Frames that currently share
            // one Antikythera child. Partition that logical assembly while the complete coherent
            // source state is still present, before Sable invokes the first per-block listener.
            if (!SableFrameRelocationService.prepareMoveOperation(sourceLevel, movedBlocks)) {
                throw new IllegalStateException(
                        "Antikythera could not safely partition a partial Frame assembly for Sable moveBlocks");
            }

            original.call(sourceLevel, transform, movedBlocks);
            completed = true;
        } finally {
            try {
                // A Frame can be copied before another macro block in the same Sable move. Keep the
                // relocation journal (and therefore its frozen structural boundary snapshot) alive
                // until Sable has copied, notified and removed the complete source set. If Sable
                // throws, leave the persisted journal fail-closed for recovery instead of committing
                // a partially moved assembly.
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

        // Recreate LevelAccelerator exactly as Sable moveBlocks does. The first pass is intentionally
        // allowed to act as a visibility warm-up for a newly allocated plot/chunk holder; the second
        // pass is the fail-closed authority. Never let Sable clear source cells after a divergent read.
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
