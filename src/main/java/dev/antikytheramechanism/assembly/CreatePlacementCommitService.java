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
 * relocation. The exceptional path exists for normal Create outcomes that are meaningful to
 * Antikythera: an indestructible target can make Create drop/skip a carried Frame, and a replaceable
 * destination can itself be another Mechanism Frame. Both are real destruction events for the
 * affected Frame's mini region and assembly graph.</p>
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
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Map<UUID, MechanismAssembly> assemblies = access.antikytheramechanism$getAssemblies();

        LinkedHashSet<UUID> ids = new LinkedHashSet<>(requestedAssemblyIds);
        if (ids.isEmpty()) {
            return CommitResult.committed(Set.of());
        }

        List<Plan> plans = new ArrayList<>();
        Map<UUID, Set<BlockPos>> displacedFramesByAssembly = new LinkedHashMap<>();
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

                    UUID previousOwner = frameIndex.get(target);
                    if (previousOwner != null
                            && !ids.contains(previousOwner)
                            && !previousOwner.equals(id)) {
                        displacedFramesByAssembly
                                .computeIfAbsent(previousOwner, ignored -> new LinkedHashSet<>())
                                .add(target);
                    }
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

        if (!hasMissingFrames && displacedFramesByAssembly.isEmpty()) {
            // Fast path: the existing implementation already provides reversible snapshots and
            // boundary reconnection when Create materialised every Frame onto unowned destinations.
            return manager.finalizeContraptionPlacement(level, ids)
                    ? CommitResult.committed(Set.of())
                    : CommitResult.failed();
        }

        for (Plan plan : plans) {
            if (access.antikytheramechanism$getContentRecoveryLocks().contains(plan.assembly().id())) {
                return CommitResult.failed();
            }
        }
        for (Map.Entry<UUID, Set<BlockPos>> entry : displacedFramesByAssembly.entrySet()) {
            UUID displacedId = entry.getKey();
            MechanismAssembly displaced = assemblies.get(displacedId);
            if (displaced == null
                    || !displaced.frames().containsAll(entry.getValue())
                    || manager.pendingPistonMove(displacedId).isPresent()
                    || manager.pendingContraptionMove(displacedId).isPresent()
                    || manager.isContentRecoveryLocked(displacedId)) {
                return CommitResult.failed();
            }
        }

        Set<UUID> assembliesToReconnect = new HashSet<>();

        /*
         * Create has already destroyed/replaced these destination Frame blocks before this method is
         * called. Their frameIndex entries were intentionally preserved through placement so we can
         * still identify the old logical owners now. Evacuate exactly those old mini regions, then
         * perform the same graph removal/split semantics as an ordinary Frame destruction. The outer
         * Frame item is not spawned here; Create's world.destroyBlock already owned that result.
         */
        for (Map.Entry<UUID, Set<BlockPos>> entry : displacedFramesByAssembly.entrySet()) {
            UUID displacedId = entry.getKey();
            MechanismAssembly displaced = assemblies.get(displacedId);
            Set<BlockPos> formerFrames = Set.copyOf(displaced.frames());

            for (BlockPos displacedFrame : entry.getValue().stream().sorted(POSITION_ORDER).toList()) {
                FrameEvacuationService.DetailedResult evacuation = FrameEvacuationService.evacuateDetailed(
                        level,
                        displaced,
                        displacedFrame,
                        FrameEvacuationService.Cause.generic());
                if (evacuation.result() != FrameEvacuationService.Result.SUCCESS) {
                    lockFailedEvacuation(
                            manager,
                            access,
                            displacedId,
                            displacedFrame,
                            evacuation,
                            "destination Frame replaced by Create");
                    return CommitResult.failed();
                }
                if (displacedId.equals(frameIndex.get(displacedFrame))) {
                    frameIndex.remove(displacedFrame);
                }
                displaced.removeFrame(displacedFrame);
            }

            if (displaced.frames().isEmpty()) {
                MechanismSubLevelService.remove(level, displaced);
                assemblies.remove(displacedId);
            } else {
                access.antikytheramechanism$splitDisconnectedAssembly(level, displaced);
                for (BlockPos survivor : formerFrames) {
                    if (entry.getValue().contains(survivor)) {
                        continue;
                    }
                    UUID owner = frameIndex.get(survivor);
                    if (owner == null || access.antikytheramechanism$getContentRecoveryLocks().contains(owner)) {
                        continue;
                    }
                    assembliesToReconnect.add(owner);
                    manager.refreshFrame(level, survivor);
                }
            }
        }
        if (!displacedFramesByAssembly.isEmpty()) {
            manager.setDirty();
        }

        if (!hasMissingFrames) {
            // With displaced ownership reconciled, reuse the manager's ordinary atomic relocation for
            // the moving assemblies instead of duplicating its rollback machinery.
            return manager.finalizeContraptionPlacement(level, ids)
                    ? CommitResult.committed(assembliesToReconnect)
                    : CommitResult.failed();
        }

        // Evacuate every definitely missing carried Frame before changing moving frameIndex/logical
        // coordinates. FrameEvacuationService therefore sees the exact immutable source mini region.
        // The final pose is supplied only for visual drop projection: the Sable body can still be on
        // its previous in-flight pose in this synchronous Create placement tick.
        for (Plan plan : plans) {
            for (BlockPos source : plan.missingSources()) {
                FrameEvacuationService.DetailedResult evacuation = FrameEvacuationService.evacuateDetailed(
                        level,
                        plan.assembly(),
                        source,
                        FrameEvacuationService.Cause.genericAtPose(plan.move().finalPose()));
                if (evacuation.result() == FrameEvacuationService.Result.SUCCESS) {
                    continue;
                }

                lockFailedEvacuation(
                        manager,
                        access,
                        plan.assembly().id(),
                        source,
                        evacuation,
                        "carried Frame skipped by Create");
                return CommitResult.failed();
            }
        }

        // Remove every stale moving source index first. A rigid transform can map one assembly's
        // target onto another source coordinate, so doing this globally avoids order-dependent writes.
        for (Plan plan : plans) {
            UUID id = plan.assembly().id();
            for (BlockPos source : plan.move().sourceFrames()) {
                if (id.equals(frameIndex.get(source))) {
                    frameIndex.remove(source);
                }
            }
        }

        for (Plan plan : plans) {
            MechanismAssembly assembly = plan.assembly();
            UUID id = assembly.id();
            if (plan.survivorTargets().isEmpty()) {
                // Create already handled the carried outer Frame item according to its own obstruction
                // and drop config. Evacuation above handled only mini contents.
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

    private static void lockFailedEvacuation(
            MechanismAssemblyManager manager,
            MechanismAssemblyManagerAccessor access,
            UUID assemblyId,
            BlockPos frame,
            FrameEvacuationService.DetailedResult evacuation,
            String circumstance) {
        if (evacuation.result() == FrameEvacuationService.Result.RECOVERY_REQUIRED) {
            access.antikytheramechanism$getPendingFrameEvacuations()
                    .put(assemblyId, java.util.Objects.requireNonNull(evacuation.recoveryJournal()));
        }
        access.antikytheramechanism$getContentRecoveryLocks().add(assemblyId);
        manager.setDirty();
        AntikytheraMechanism.LOGGER.error(
                "Locked assembly {} because {} at {} could not be evacuated exactly. "
                        + "Persistent recovery state was retained and committed drops will not be duplicated.",
                assemblyId,
                circumstance,
                frame);
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
