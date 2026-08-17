package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.FrameEvacuationService;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Commits a Create placement after Create has applied its own replacement policy.
 *
 * <p>The ordinary all-Frames-present path delegates to MechanismAssemblyManager's existing atomic
 * relocation. The exceptional path exists for a normal Create outcome: an indestructible target can
 * make Create drop/skip one carried Frame while successfully placing the rest. Such a skipped Frame
 * is a real destruction event for Antikythera: evacuate its immutable eight mini cells, remove the
 * physical node, and split the surviving graph if the missing node was a bridge.</p>
 */
public final class CreatePlacementCommitService {
    private CreatePlacementCommitService() {
    }

    public record CommitResult(boolean committed, Set<UUID> assembliesToReconnect) {
        public CommitResult {
            assembliesToReconnect = Set.copyOf(assembliesToReconnect);
        }

        public static CommitResult failed() {
            return new CommitResult(false, Set.of());
        }

        public static CommitResult committed(Set<UUID> assembliesToReconnect) {
            return new CommitResult(true, assembliesToReconnect);
        }
    }

    public static CommitResult finalizePreparedPlacement(
            ServerLevel level,
            Collection<UUID> requestedAssemblyIds) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(requestedAssemblyIds);
        if (ids.isEmpty()) {
            return CommitResult.committed(Set.of());
        }

        List<Plan> plans = new ArrayList<>();
        boolean hasMissingFrames = false;
        for (UUID id : ids) {
            PendingContraptionMove move = manager.pendingContraptionMove(id).orElse(null);
            MechanismAssembly assembly = manager.getAssembly(id).orElse(null);
            if (move == null
                    || assembly == null
                    || !move.hasPlacement()
                    || !assembly.frames().equals(move.sourceFrames())) {
                return CommitResult.failed();
            }

            FrameOrientation sourceOrientation = FrameOrientation.fromQuaternion(
                    move.startPose().orientation(new Quaterniond())).orElse(null);
            FrameOrientation targetOrientation = FrameOrientation.fromQuaternion(
                    move.finalPose().orientation(new Quaterniond())).orElse(null);
            if (sourceOrientation == null || targetOrientation == null) {
                return CommitResult.failed();
            }

            Map<BlockPos, BlockPos> targetBySource = new LinkedHashMap<>();
            Set<BlockPos> survivorTargets = new LinkedHashSet<>();
            Set<BlockPos> missingSources = new LinkedHashSet<>();
            List<BlockPos> orderedSources = move.sourceFrames().stream()
                    .sorted(POSITION_ORDER)
                    .toList();
            for (BlockPos source : orderedSources) {
                BlockPos expectedLogical = sourceOrientation.toLogical(source.subtract(move.sourceOrigin()));
                if (!expectedLogical.equals(assembly.logicalFrameOffset(source))) {
                    return CommitResult.failed();
                }
                BlockPos target = move.targetOrigin()
                        .offset(targetOrientation.toPhysical(expectedLogical))
                        .immutable();
                if (!move.targetFrames().contains(target) || !level.hasChunkAt(target)) {
                    return CommitResult.failed();
                }
                targetBySource.put(source.immutable(), target);

                if (level.getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())) {
                    // A Frame state without its BE is not proof that Create skipped the block. Treat
                    // that as a transient/failed placement and retain the recovery journal instead of
                    // evacuating content that may still belong to a materialising Frame.
                    if (!(level.getBlockEntity(target) instanceof MechanismFrameBlockEntity)) {
                        return CommitResult.failed();
                    }
                    survivorTargets.add(target);
                } else {
                    missingSources.add(source.immutable());
                    hasMissingFrames = true;
                }
            }
            plans.add(new Plan(
                    assembly,
                    move,
                    targetOrientation,
                    Map.copyOf(targetBySource),
                    Set.copyOf(survivorTargets),
                    Set.copyOf(missingSources)));
        }

        if (!hasMissingFrames) {
            // This path already performs rollback snapshots and boundary reconnection.
            return manager.finalizeContraptionPlacement(level, ids)
                    ? CommitResult.committed(Set.of())
                    : CommitResult.failed();
        }

        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        for (Plan plan : plans) {
            if (access.antikytheramechanism$getContentRecoveryLocks().contains(plan.assembly().id())) {
                return CommitResult.failed();
            }
        }

        // Evacuate every definitely missing physical Frame before changing frameIndex or logical
        // coordinates. FrameEvacuationService therefore sees the exact immutable source mini region.
        for (Plan plan : plans) {
            for (BlockPos source : plan.missingSources()) {
                FrameEvacuationService.DetailedResult evacuation = FrameEvacuationService.evacuateDetailed(
                        level,
                        plan.assembly(),
                        source,
                        FrameEvacuationService.Cause.generic());
                if (evacuation.result() == FrameEvacuationService.Result.SUCCESS) {
                    continue;
                }

                UUID id = plan.assembly().id();
                if (evacuation.result() == FrameEvacuationService.Result.RECOVERY_REQUIRED) {
                    access.antikytheramechanism$getPendingFrameEvacuations()
                            .put(id, java.util.Objects.requireNonNull(evacuation.recoveryJournal()));
                }
                access.antikytheramechanism$getContentRecoveryLocks().add(id);
                manager.setDirty();
                AntikytheraMechanism.LOGGER.error(
                        "Locked Create placement for assembly {} because skipped Frame {} could not be evacuated exactly. "
                                + "The contraption journal and remaining mini content were retained; already committed generic drops are not duplicated.",
                        id,
                        source);
                return CommitResult.failed();
            }
        }

        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Map<UUID, MechanismAssembly> assemblies = access.antikytheramechanism$getAssemblies();

        // Remove every stale source index first. A rigid transform can map one assembly's target onto
        // another source coordinate, so doing this globally avoids order-dependent ownership writes.
        for (Plan plan : plans) {
            UUID id = plan.assembly().id();
            for (BlockPos source : plan.move().sourceFrames()) {
                if (id.equals(frameIndex.get(source))) {
                    frameIndex.remove(source);
                }
            }
        }

        Set<UUID> assembliesToReconnect = new HashSet<>();
        for (Plan plan : plans) {
            MechanismAssembly assembly = plan.assembly();
            UUID id = assembly.id();
            if (plan.survivorTargets().isEmpty()) {
                // Create already handled the outer Frame item according to its own obstruction/drop
                // config. Evacuation above handled only mini contents, so removing the now-empty
                // assembly cannot duplicate the Frame item.
                MechanismSubLevelService.remove(level, assembly);
                assemblies.remove(id);
                continue;
            }

            assembly.relocate(plan.move().targetOrigin(), plan.survivorTargets(), plan.targetOrientation());
            assembly.setPoseTarget(plan.move().finalPose());
            for (BlockPos target : plan.survivorTargets()) {
                frameIndex.put(target, id);
                if (level.getBlockEntity(target) instanceof MechanismFrameBlockEntity frame) {
                    frame.setAssemblyMapping(id, plan.targetOrientation(), assembly.logicalFrameOffset(target));
                }
            }
        }

        // The pending move is deliberately removed only after all physical-loss evacuations and
        // ownership writes succeeded. splitDisconnectedAssembly refuses to run while it exists.
        for (Plan plan : plans) {
            UUID id = plan.assembly().id();
            access.antikytheramechanism$getPendingContraptionMoves().remove(id);
            access.antikytheramechanism$getInvalidContraptionMovesLogged().remove(id);
        }
        manager.setDirty();

        for (Plan plan : plans) {
            if (plan.survivorTargets().isEmpty()) {
                continue;
            }
            MechanismAssembly assembly = assemblies.get(plan.assembly().id());
            if (assembly == null) {
                continue;
            }

            // If the omitted Frame was an articulation point, reuse the same split transaction as a
            // normal world break. Content transfers therefore keep component UUID/sublevel ownership
            // coherent instead of leaving a disconnected graph under one assembly id.
            access.antikytheramechanism$splitDisconnectedAssembly(level, assembly);

            for (BlockPos target : plan.survivorTargets()) {
                UUID owner = frameIndex.get(target);
                if (owner == null || access.antikytheramechanism$getContentRecoveryLocks().contains(owner)) {
                    continue;
                }
                assembliesToReconnect.add(owner);
                manager.refreshFrame(level, target);
            }
        }
        manager.setDirty();
        return CommitResult.committed(assembliesToReconnect);
    }

    private record Plan(
            MechanismAssembly assembly,
            PendingContraptionMove move,
            FrameOrientation targetOrientation,
            Map<BlockPos, BlockPos> targetBySource,
            Set<BlockPos> survivorTargets,
            Set<BlockPos> missingSources) {
    }

    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos pos) -> pos.getY())
                    .thenComparingInt(pos -> pos.getZ())
                    .thenComparingInt(pos -> pos.getX());
}
