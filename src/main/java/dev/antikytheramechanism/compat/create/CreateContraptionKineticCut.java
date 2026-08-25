package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.mixin.CreateRotationPropagatorAccessor;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cuts only live Create source edges invalidated by a Mechanism Frame physical-host transition.
 *
 * <p>Create stores the kinetic graph as directed immediate-source relations. Once a relocation journal
 * exists, moving assemblies are excluded from virtual-neighbour discovery; every source edge crossing
 * the moving/static partition is therefore stale and can be repaired through Create's ordinary
 * missing-source propagation without tearing down unrelated network components.</p>
 */
final class CreateContraptionKineticCut {
    private CreateContraptionKineticCut() {
    }

    static void disconnect(
            ServerLevel level,
            Collection<MechanismAssembly> cohort,
            Collection<UUID> movingAssemblyIds) {
        disconnect(level, cohort, movingAssemblyIds, List.of());
    }

    /**
     * Variant that also treats nearby Antikythera Transmission Boxes as stationary macro endpoints.
     * This is required because a managed mini KBE can legitimately store a Transmission Box BlockPos
     * as its Create source; the historical mini-only cut could not classify that source and left the
     * moving mini network powered after its Frame had already entered a contraption/SubLevel journal.
     */
    static void disconnect(
            ServerLevel level,
            Collection<MechanismAssembly> cohort,
            Collection<UUID> movingAssemblyIds,
            Collection<TransmissionBoxBlockEntity> boundaryBoxes) {
        Map<BlockPos, MiniNode> miniNodes = collectKinetics(level, cohort);
        Map<BlockPos, TransmissionBoxBlockEntity> boxes = collectBoxes(boundaryBoxes);
        if (miniNodes.isEmpty() && boxes.isEmpty()) {
            return;
        }

        Set<UUID> moving = new HashSet<>(movingAssemblyIds);
        if (moving.isEmpty()) {
            return;
        }

        List<KineticBlockEntity> ordered = java.util.stream.Stream.concat(
                        miniNodes.values().stream().map(MiniNode::kinetic),
                        boxes.values().stream().map(box -> (KineticBlockEntity) box))
                .distinct()
                .sorted(Comparator.comparingLong(node -> node.getBlockPos().asLong()))
                .toList();

        // Re-scan after every repair: propagateMissingSource may re-source a whole dependent subtree.
        int guard = Math.max(1, ordered.size() * 3);
        while (guard-- > 0) {
            KineticBlockEntity dependent = findCrossingDependent(miniNodes, boxes, ordered, moving);
            if (dependent == null) {
                break;
            }
            CreateRotationPropagatorAccessor.antikytheramechanism$propagateMissingSource(dependent);
        }

        KineticBlockEntity unresolved = findCrossingDependent(miniNodes, boxes, ordered, moving);
        if (unresolved != null) {
            AntikytheraMechanism.LOGGER.error(
                    "Create physical-host kinetic cut could not eliminate a stale source at {}",
                    unresolved.getBlockPos());
        }

        // Pending-move eligibility still hides every moving/static virtual edge here, so advertising
        // the remaining nodes can only restore valid same-side/internal relations.
        refreshNodes(miniNodes.values().stream()
                .sorted(Comparator.comparingLong(node -> node.kinetic().getBlockPos().asLong()))
                .toList());
        for (TransmissionBoxBlockEntity box : boxes.values()) {
            if (!box.isRemoved()) {
                box.attachKinetics();
            }
        }
    }

    /** Re-advertises healthy managed mini nodes after placement without destroying their networks. */
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

    private static KineticBlockEntity findCrossingDependent(
            Map<BlockPos, MiniNode> miniNodes,
            Map<BlockPos, TransmissionBoxBlockEntity> boxes,
            List<KineticBlockEntity> ordered,
            Set<UUID> moving) {
        for (KineticBlockEntity dependent : ordered) {
            if (dependent.isRemoved() || !dependent.hasSource() || dependent.source == null) {
                continue;
            }

            Boolean dependentMoving = movingState(dependent.getBlockPos(), miniNodes, boxes, moving);
            Boolean sourceMoving = movingState(dependent.source, miniNodes, boxes, moving);
            if (dependentMoving == null || sourceMoving == null || dependentMoving == sourceMoving) {
                continue;
            }
            return dependent;
        }
        return null;
    }

    /** null means the endpoint is outside the boundary cohort and is deliberately left to Create. */
    private static Boolean movingState(
            BlockPos position,
            Map<BlockPos, MiniNode> miniNodes,
            Map<BlockPos, TransmissionBoxBlockEntity> boxes,
            Set<UUID> moving) {
        MiniNode mini = miniNodes.get(position);
        if (mini != null) {
            return moving.contains(mini.assembly().id());
        }
        if (boxes.containsKey(position)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Map<BlockPos, TransmissionBoxBlockEntity> collectBoxes(
            Collection<TransmissionBoxBlockEntity> boundaryBoxes) {
        Map<BlockPos, TransmissionBoxBlockEntity> result = new LinkedHashMap<>();
        for (TransmissionBoxBlockEntity box : boundaryBoxes) {
            if (box != null && !box.isRemoved()) {
                result.put(box.getBlockPos().immutable(), box);
            }
        }
        return result;
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
