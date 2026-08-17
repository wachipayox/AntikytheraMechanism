package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.mixin.CreateRotationPropagatorAccessor;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cuts only the live Create source edges invalidated by a Mechanism Frame contraption capture.
 *
 * <p>Create does not represent a kinetic graph as an independent undirected edge set. Every passive
 * node stores its immediate upstream {@link KineticBlockEntity#source}, and removing one source asks
 * {@code RotationPropagator.propagateMissingSource} to clear and re-source the dependent subtree.
 * Calling {@link KineticBlockEntity#detachKinetics()} for every node in an otherwise healthy graph
 * therefore interleaves many destructive repair traversals. With a larger gear train that can leave
 * alternating nodes attached to stale network/source state.</p>
 *
 * <p>Once a contraption move is journaled, moving assemblies are already excluded from virtual
 * cross-assembly neighbour discovery. At that point every stored source relation crossing the
 * moving/static partition is necessarily stale. Repairing the dependent endpoint of exactly those
 * directed edges lets Create perform its normal missing-source traversal while the forbidden virtual
 * edge is invisible, without disturbing unrelated source trees.</p>
 */
final class CreateContraptionKineticCut {
    private CreateContraptionKineticCut() {
    }

    static void disconnect(
            ServerLevel level,
            Collection<MechanismAssembly> cohort,
            Collection<UUID> movingAssemblyIds) {
        Map<BlockPos, MiniNode> nodes = collectKinetics(level, cohort);
        if (nodes.isEmpty()) {
            return;
        }
        Set<UUID> moving = new HashSet<>(movingAssemblyIds);
        if (moving.isEmpty()) {
            return;
        }

        List<MiniNode> ordered = nodes.values().stream()
                .sorted(Comparator.comparingLong(node -> node.kinetic().getBlockPos().asLong()))
                .toList();

        // Each invocation must remove at least the currently selected crossing source relation.
        // Re-scan after every repair because Create may have re-sourced other nodes in the subtree.
        int guard = Math.max(1, nodes.size() * 2);
        while (guard-- > 0) {
            MiniNode dependent = findCrossingDependent(nodes, ordered, moving);
            if (dependent == null) {
                break;
            }
            CreateRotationPropagatorAccessor.antikytheramechanism$propagateMissingSource(
                    dependent.kinetic());
        }

        MiniNode unresolved = findCrossingDependent(nodes, ordered, moving);
        if (unresolved != null) {
            AntikytheraMechanism.LOGGER.error(
                    "Create contraption kinetic cut could not eliminate a stale cross-assembly source at {}",
                    unresolved.kinetic().getBlockPos());
        }

        // Re-advertise the now-consistent components without clearing them. Pending-move eligibility
        // still hides every moving/static virtual edge, so this can only restore valid internal or
        // same-side relations and reactivate generators that had been overpowered across the cut.
        refreshNodes(ordered);
    }

    /**
     * Advertises the current healthy nodes to Create without first destroying their network state.
     * Used after placement when virtual diagonals become eligible again.
     */
    static void refresh(ServerLevel level, Collection<MechanismAssembly> assemblies) {
        Map<BlockPos, MiniNode> nodes = collectKinetics(level, assemblies);
        if (nodes.isEmpty()) {
            return;
        }
        List<MiniNode> ordered = nodes.values().stream()
                .sorted(Comparator.comparingLong(node -> node.kinetic().getBlockPos().asLong()))
                .toList();
        refreshNodes(ordered);
    }

    private static void refreshNodes(List<MiniNode> ordered) {
        // A generator may have been a dependent of a stronger external source. Missing-source repair
        // correctly removes that source and marks GeneratingKineticBlockEntity for reactivation; do
        // it now so subsequent passive advertisements see a real source immediately.
        for (MiniNode node : ordered) {
            KineticBlockEntity kinetic = node.kinetic();
            if (kinetic.isRemoved()) {
                continue;
            }
            if (kinetic instanceof GeneratingKineticBlockEntity generator
                    && Math.abs(generator.getGeneratedSpeed()) > 1.0E-6F) {
                generator.updateGeneratedRotation();
            }
        }
        for (MiniNode node : ordered) {
            KineticBlockEntity kinetic = node.kinetic();
            if (!kinetic.isRemoved()) {
                kinetic.attachKinetics();
            }
        }
    }

    private static MiniNode findCrossingDependent(
            Map<BlockPos, MiniNode> nodes,
            List<MiniNode> ordered,
            Set<UUID> moving) {
        for (MiniNode dependent : ordered) {
            KineticBlockEntity kinetic = dependent.kinetic();
            if (kinetic.isRemoved() || !kinetic.hasSource() || kinetic.source == null) {
                continue;
            }
            MiniNode source = nodes.get(kinetic.source);
            if (source == null || source.kinetic().isRemoved()) {
                continue;
            }
            boolean dependentMoving = moving.contains(dependent.assembly().id());
            boolean sourceMoving = moving.contains(source.assembly().id());
            if (dependentMoving != sourceMoving) {
                return dependent;
            }
        }
        return null;
    }

    private static Map<BlockPos, MiniNode> collectKinetics(
            ServerLevel level,
            Collection<MechanismAssembly> assemblies) {
        Map<BlockPos, MiniNode> result = new LinkedHashMap<>();
        Set<UUID> visited = new HashSet<>();
        for (MechanismAssembly assembly : assemblies) {
            if (assembly == null || !visited.add(assembly.id())) {
                continue;
            }
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            for (BlockPos frame : assembly.frames()) {
                for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                    for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                        for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                            BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                            BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                            if (!level.hasChunkAt(global)) {
                                continue;
                            }
                            BlockEntity blockEntity = level.getBlockEntity(global);
                            if (blockEntity instanceof KineticBlockEntity kinetic) {
                                result.put(global.immutable(), new MiniNode(assembly, kinetic));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private record MiniNode(MechanismAssembly assembly, KineticBlockEntity kinetic) {
    }
}
