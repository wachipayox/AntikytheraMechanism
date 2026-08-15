package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gives Antikythera visibility of the complete synchronous Sable relocation operation. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SubLevelAssemblyMoveContextMixin {
    private static final ThreadLocal<ArrayDeque<ManagedTransferTrace>> antikythera$managedTransferTraces =
            ThreadLocal.withInitial(ArrayDeque::new);

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
        if (trace != null) {
            antikythera$managedTransferTraces.get().push(trace);
        }
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
                try {
                    SableAssemblyMoveContext.end();
                } finally {
                    if (trace != null) {
                        ArrayDeque<ManagedTransferTrace> traces = antikythera$managedTransferTraces.get();
                        if (!traces.isEmpty() && traces.peek() == trace) {
                            traces.pop();
                        } else {
                            traces.remove(trace);
                        }
                        if (traces.isEmpty()) {
                            antikythera$managedTransferTraces.remove();
                        }
                    }
                }
            }
        }
    }

    /** Records exactly what Sable asks LevelChunk to write during a managed child transfer. */
    @WrapOperation(
            method = "moveBlocks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState antikythera$traceManagedTransferChunkWrite(
            LevelChunk chunk,
            BlockPos position,
            BlockState state,
            boolean moving,
            Operation<BlockState> original) {
        BlockState previous = original.call(chunk, position, state, moving);
        ArrayDeque<ManagedTransferTrace> traces = antikythera$managedTransferTraces.get();
        if (!traces.isEmpty()) {
            traces.peek().recordWrite(position, state, previous);
        }
        return previous;
    }

    /**
     * Narrow diagnostic for Antikythera child -> child transfers. It records only non-air source
     * states and is intentionally inert for ordinary Sable assembly/disassembly and host movement.
     */
    private static final class ManagedTransferTrace {
        private final ServerSubLevel source;
        private final ServerSubLevel target;
        private final List<BlockPos> sources;
        private final List<BlockState> expectedStates;
        private final Set<BlockPos> allSourcePositions;
        private final Set<BlockPos> allTargetPositions;
        private final boolean sourceTargetOverlap;
        private final boolean duplicateTargets;
        private final List<String> writes = new ArrayList<>();
        private final List<String> fastReadMismatches = new ArrayList<>();

        private ManagedTransferTrace(
                ServerSubLevel source,
                ServerSubLevel target,
                List<BlockPos> sources,
                List<BlockState> expectedStates,
                Set<BlockPos> allSourcePositions,
                Set<BlockPos> allTargetPositions,
                boolean sourceTargetOverlap,
                boolean duplicateTargets) {
            this.source = source;
            this.target = target;
            this.sources = sources;
            this.expectedStates = expectedStates;
            this.allSourcePositions = allSourcePositions;
            this.allTargetPositions = allTargetPositions;
            this.sourceTargetOverlap = sourceTargetOverlap;
            this.duplicateTargets = duplicateTargets;
        }

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
            LevelAccelerator fastReader = new LevelAccelerator(level);
            List<String> fastMismatches = new ArrayList<>();
            for (BlockPos sourcePosition : movedBlocks) {
                BlockPos targetPosition = transform.apply(sourcePosition).immutable();
                overlap |= sourceSet.contains(targetPosition);
                duplicates |= !targetSet.add(targetPosition);
                BlockState state = level.getBlockState(sourcePosition);
                BlockState fastState = fastReader.getBlockState(sourcePosition);
                if (!fastState.equals(state)) {
                    fastMismatches.add(sourcePosition + ": regular=" + state + ", accelerated=" + fastState);
                }
                if (!state.isAir()) {
                    nonAirSources.add(sourcePosition);
                    nonAirStates.add(state);
                }
            }
            ManagedTransferTrace trace = new ManagedTransferTrace(
                    source,
                    target,
                    List.copyOf(nonAirSources),
                    List.copyOf(nonAirStates),
                    Set.copyOf(sourceSet),
                    Set.copyOf(targetSet),
                    overlap,
                    duplicates);
            trace.fastReadMismatches.addAll(fastMismatches);
            return trace;
        }

        private void recordWrite(BlockPos position, BlockState state, BlockState previous) {
            BlockPos immutable = position.immutable();
            if (!state.isAir()
                    || allTargetPositions.contains(immutable)
                    || allSourcePositions.contains(immutable)) {
                writes.add(immutable + ": state=" + state + ", previous=" + previous);
            }
        }

        private void verify(ServerLevel level, AssemblyTransform transform) {
            for (int index = 0; index < sources.size(); index++) {
                BlockPos sourcePosition = sources.get(index);
                BlockPos targetPosition = transform.apply(sourcePosition).immutable();
                BlockState expected = expectedStates.get(index);
                BlockState actual = level.getBlockState(targetPosition);
                if (!actual.equals(expected)) {
                    AntikytheraMechanism.LOGGER.error(
                            "Managed child moveBlocks mismatch: sourceChild={} sourceCenter={} targetChild={} targetCenter={} source={} target={} expected={} actual={} sourceTargetOverlap={} duplicateTargets={} fastReadMismatches={} writes={}",
                            source.getUniqueId(),
                            source.getPlot().getCenterBlock(),
                            target.getUniqueId(),
                            target.getPlot().getCenterBlock(),
                            sourcePosition,
                            targetPosition,
                            expected,
                            actual,
                            sourceTargetOverlap,
                            duplicateTargets,
                            fastReadMismatches,
                            writes);
                }
            }
        }
    }
}
