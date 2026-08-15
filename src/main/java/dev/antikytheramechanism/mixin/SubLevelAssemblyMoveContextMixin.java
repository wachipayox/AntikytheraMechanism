package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gives Antikythera visibility of the complete synchronous Sable relocation operation. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SubLevelAssemblyMoveContextMixin {
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

        ManagedTransferTrace trace = ManagedTransferTrace.capture(sourceLevel, transform, movedBlocks);
        SableAssemblyMoveContext.begin(sourceLevel, transform, movedBlocks);
        boolean completed = false;
        try {
            // A Sable host split can move only a strict subset of the Frames that currently share
            // one Antikythera child. Partition that logical assembly while the complete coherent
            // source state is still present, before Sable invokes the first per-block listener.
            if (!SableFrameRelocationService.prepareMoveOperation(sourceLevel, movedBlocks)) {
                throw new IllegalStateException(
                        "Antikythera could not safely partition a partial Frame assembly for Sable moveBlocks");
            }

            original.call(sourceLevel, transform, movedBlocks);
            completed = true;
            if (trace != null) {
                trace.verify(sourceLevel, transform);
            }
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

    /**
     * Narrow diagnostic for Antikythera child -> child transfers. It records only non-air source
     * states and is intentionally inert for ordinary Sable assembly/disassembly and host movement.
     */
    private record ManagedTransferTrace(
            ServerSubLevel source,
            ServerSubLevel target,
            List<BlockPos> sources,
            List<BlockState> expectedStates,
            boolean sourceTargetOverlap,
            boolean duplicateTargets) {
        private static ManagedTransferTrace capture(
                ServerLevel level,
                AssemblyTransform transform,
                List<BlockPos> movedBlocks) {
            if (movedBlocks.isEmpty()) {
                return null;
            }
            SubLevel rawSource = Sable.HELPER.getContaining(level, movedBlocks.getFirst());
            SubLevel rawTarget = Sable.HELPER.getContaining(level, transform.apply(movedBlocks.getFirst()));
            if (!(rawSource instanceof ServerSubLevel source)
                    || !(rawTarget instanceof ServerSubLevel target)
                    || source == target
                    || MechanismSubLevelService.getOwnerAssemblyId(source) == null
                    || MechanismSubLevelService.getOwnerAssemblyId(target) == null) {
                return null;
            }

            Set<BlockPos> sourceSet = new HashSet<>(movedBlocks);
            Set<BlockPos> targetSet = new HashSet<>();
            List<BlockPos> nonAirSources = new ArrayList<>();
            List<BlockState> nonAirStates = new ArrayList<>();
            boolean overlap = false;
            boolean duplicates = false;
            for (BlockPos sourcePosition : movedBlocks) {
                BlockPos targetPosition = transform.apply(sourcePosition).immutable();
                overlap |= sourceSet.contains(targetPosition);
                duplicates |= !targetSet.add(targetPosition);
                BlockState state = level.getBlockState(sourcePosition);
                if (!state.isAir()) {
                    nonAirSources.add(sourcePosition);
                    nonAirStates.add(state);
                }
            }
            return new ManagedTransferTrace(
                    source,
                    target,
                    List.copyOf(nonAirSources),
                    List.copyOf(nonAirStates),
                    overlap,
                    duplicates);
        }

        private void verify(ServerLevel level, AssemblyTransform transform) {
            for (int index = 0; index < sources.size(); index++) {
                BlockPos sourcePosition = sources.get(index);
                BlockPos targetPosition = transform.apply(sourcePosition).immutable();
                BlockState expected = expectedStates.get(index);
                BlockState actual = level.getBlockState(targetPosition);
                if (!actual.equals(expected)) {
                    AntikytheraMechanism.LOGGER.error(
                            "Managed child moveBlocks mismatch: sourceChild={} sourceCenter={} targetChild={} targetCenter={} source={} target={} expected={} actual={} sourceTargetOverlap={} duplicateTargets={}",
                            source.getUniqueId(),
                            source.getPlot().getCenterBlock(),
                            target.getUniqueId(),
                            target.getPlot().getCenterBlock(),
                            sourcePosition,
                            targetPosition,
                            expected,
                            actual,
                            sourceTargetOverlap,
                            duplicateTargets);
                }
            }
        }
    }
}
