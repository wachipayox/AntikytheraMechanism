package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
 * Adapts Create's ordinary kinetic graph to the physical half-block lattice exposed by static Frames.
 *
 * <p>Blocks inside one MechanismAssembly already share one contiguous Sable plot, so Create handles
 * those connections natively. Blocks belonging to different assemblies live in unrelated plot-yard
 * coordinates even when their visible mini cells are diagonally adjacent in the parent world. This
 * class supplies only that missing spatial relation. Rotation ratios, source selection, stress and
 * network ownership remain Create's responsibility.</p>
 */
public final class CreateMiniKineticTopology {
    private static final double ALIGNMENT_EPSILON = 1.0E-5;

    private CreateMiniKineticTopology() {
    }

    /** Adds real plot positions whose visible mini cells are one face-diagonal away. */
    public static void appendVirtualDiagonalNeighbours(
            KineticBlockEntity sourceBlockEntity,
            List<BlockPos> neighbours) {
        if (!(sourceBlockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Node source = resolveNode(level, sourceBlockEntity.getBlockPos());
        if (source == null || !eligibleForCrossAssemblyLinks(level, source.assembly())) {
            return;
        }

        Set<BlockPos> known = new HashSet<>(neighbours);
        for (Node candidate : crossAssemblyNodes(level)) {
            if (candidate.assembly().id().equals(source.assembly().id())) {
                continue;
            }
            BlockPos visibleDiff = candidate.physicalMini().subtract(source.physicalMini());
            if (isFaceDiagonal(visibleDiff) && known.add(candidate.globalPlotPosition())) {
                neighbours.add(candidate.globalPlotPosition());
            }
        }
    }

    /**
     * Replaces Create's plot-yard position delta only for a virtual cross-assembly diagonal. Create's
     * own getRotationSpeedModifier then evaluates the exact ordinary cog rules against this delta.
     */
    public static BlockPos relativePosition(
            KineticBlockEntity from,
            KineticBlockEntity to,
            BlockPos vanillaDifference) {
        if (!(from.getLevel() instanceof ServerLevel level) || to.getLevel() != level) {
            return vanillaDifference;
        }
        Node source = resolveNode(level, from.getBlockPos());
        Node target = resolveNode(level, to.getBlockPos());
        if (source == null || target == null
                || source.assembly().id().equals(target.assembly().id())
                || !eligibleForCrossAssemblyLinks(level, source.assembly())
                || !eligibleForCrossAssemblyLinks(level, target.assembly())) {
            return vanillaDifference;
        }
        BlockPos visibleDiff = target.physicalMini().subtract(source.physicalMini());
        return isFaceDiagonal(visibleDiff) ? visibleDiff : vanillaDifference;
    }

    /** Detaches complete managed kinetic graphs before their blocks are transactionally transferred. */
    public static void quiesceAssemblies(ServerLevel level, Collection<MechanismAssembly> assemblies) {
        Map<BlockPos, KineticBlockEntity> nodes = collectKinetics(level, assemblies);
        for (KineticBlockEntity kinetic : nodes.values()) {
            if (kinetic.isRemoved()) {
                continue;
            }
            if (kinetic.hasSource() || kinetic.isSource() || kinetic.hasNetwork()) {
                kinetic.detachKinetics();
            }
        }
        for (KineticBlockEntity kinetic : nodes.values()) {
            if (kinetic.isRemoved()) {
                continue;
            }
            kinetic.removeSource();
            kinetic.clearKineticInformation();
            kinetic.setChanged();
        }
    }

    /**
     * Rebuilds only after the assembly transaction has fully committed. Generators are reactivated
     * first so passive nodes never consume their one attach attempt while the source still has speed 0.
     */
    public static void rebuildAssemblies(ServerLevel level, Collection<MechanismAssembly> assemblies) {
        Map<BlockPos, KineticBlockEntity> nodes = collectKinetics(level, assemblies);
        if (nodes.isEmpty()) {
            return;
        }

        quiesceNodes(nodes.values());

        List<KineticBlockEntity> ordered = nodes.values().stream()
                .sorted(Comparator.comparingLong(kinetic -> kinetic.getBlockPos().asLong()))
                .toList();
        Set<KineticBlockEntity> activatedGenerators = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());

        for (KineticBlockEntity kinetic : ordered) {
            if (kinetic instanceof GeneratingKineticBlockEntity generator
                    && Math.abs(generator.getGeneratedSpeed()) > 1.0E-6F) {
                generator.updateGeneratedRotation();
                activatedGenerators.add(kinetic);
            }
        }
        for (KineticBlockEntity kinetic : ordered) {
            if (activatedGenerators.contains(kinetic) || kinetic.isRemoved()) {
                continue;
            }
            if (!kinetic.hasSource() && !kinetic.hasNetwork()) {
                kinetic.attachKinetics();
            }
        }
    }

    private static void quiesceNodes(Collection<KineticBlockEntity> nodes) {
        for (KineticBlockEntity kinetic : nodes) {
            if (kinetic.isRemoved()) {
                continue;
            }
            if (kinetic.hasSource() || kinetic.isSource() || kinetic.hasNetwork()) {
                kinetic.detachKinetics();
            }
        }
        for (KineticBlockEntity kinetic : nodes) {
            if (kinetic.isRemoved()) {
                continue;
            }
            kinetic.removeSource();
            kinetic.clearKineticInformation();
            kinetic.setChanged();
        }
    }

    private static Map<BlockPos, KineticBlockEntity> collectKinetics(
            ServerLevel level,
            Collection<MechanismAssembly> assemblies) {
        Map<BlockPos, KineticBlockEntity> result = new LinkedHashMap<>();
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
                                result.put(global.immutable(), kinetic);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<Node> crossAssemblyNodes(ServerLevel level) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<Node> result = new ArrayList<>();
        for (MechanismAssembly assembly : manager.assemblies()) {
            if (!eligibleForCrossAssemblyLinks(level, assembly)) {
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
                            if (!level.hasChunkAt(global)
                                    || !(level.getBlockEntity(global) instanceof KineticBlockEntity)) {
                                continue;
                            }
                            result.add(new Node(
                                    assembly,
                                    global.immutable(),
                                    physicalMiniPosition(assembly, mini)));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Node resolveNode(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }
        UUID assemblyId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (assemblyId == null) {
            return null;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(assemblyId).orElse(null);
        if (assembly == null) {
            return null;
        }
        BlockPos mini = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, mini)) {
            return null;
        }
        return new Node(assembly, globalPlotPosition.immutable(), physicalMiniPosition(assembly, mini));
    }

    /** Integer half-block lattice: every ordinary macro block spans exactly two coordinates per axis. */
    private static BlockPos physicalMiniPosition(MechanismAssembly assembly, BlockPos mini) {
        BlockPos frame = MiniCoordinateMapper.miniToFrame(assembly, mini);
        BlockPos logicalCell = MiniCoordinateMapper.cellInFrame(mini);
        BlockPos physicalCell = assembly.orientation().logicalCellToPhysical(
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        return new BlockPos(
                frame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getX(),
                frame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getY(),
                frame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getZ());
    }

    private static boolean eligibleForCrossAssemblyLinks(ServerLevel level, MechanismAssembly assembly) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        return !manager.isContentRecoveryLocked(assembly.id())
                && manager.pendingPistonMove(assembly.id()).isEmpty()
                && manager.pendingContraptionMove(assembly.id()).isEmpty()
                && MechanismAssemblyHost.resolve(level, assembly.origin()).kind() == MechanismAssemblyHost.Kind.ROOT
                && MechanismAssemblyHost.boundaryIsAligned(level, assembly, ALIGNMENT_EPSILON);
    }

    private static boolean isFaceDiagonal(BlockPos difference) {
        int x = Math.abs(difference.getX());
        int y = Math.abs(difference.getY());
        int z = Math.abs(difference.getZ());
        return x <= 1 && y <= 1 && z <= 1 && x + y + z == 2;
    }

    private record Node(
            MechanismAssembly assembly,
            BlockPos globalPlotPosition,
            BlockPos physicalMini) {
    }
}
