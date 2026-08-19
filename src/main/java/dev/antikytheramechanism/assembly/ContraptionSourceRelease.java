package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains the distinction between a Create journal's historical source footprint and the subset
 * of source positions that Create has already physically vacated.
 *
 * <p>Released positions remain in {@link PendingContraptionMove#sourceFrames()} for crash recovery
 * and rigid placement mapping, but they stop reserving {@code frameIndex}. That lets an unrelated
 * Frame legitimately reuse the macro coordinate while the old contraption is still in flight.</p>
 */
public final class ContraptionSourceRelease {
    private static final String ASSEMBLIES_TAG = "assemblies";
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String ASSEMBLY_RECORD_ID_TAG = "id";
    private static final String PENDING_CONTRAPTION_MOVES_TAG = "pending_contraption_moves";
    private static final String CONTENT_RECOVERY_LOCKS_TAG = "content_recovery_locks";

    private ContraptionSourceRelease() {
    }

    /**
     * Releases one source only during Create extraction. Sable relocation already has a placement
     * journal before it copies/removes Frames and therefore deliberately does not enter this path.
     */
    public static boolean release(MechanismAssemblyManager manager, BlockPos source) {
        MechanismAssemblyManagerAccessor access = access(manager);
        UUID owner = access.antikytheramechanism$getFrameIndex().get(source);
        if (owner == null) {
            return false;
        }
        PendingContraptionMove move = access.antikytheramechanism$getPendingContraptionMoves().get(owner);
        if (move == null
                || move.hasPlacement()
                || !move.sourceFrames().contains(source)
                || move.isSourceReleased(source)) {
            return false;
        }

        PendingContraptionMove released = move.withReleasedSource(source);
        access.antikytheramechanism$getPendingContraptionMoves().put(owner, released);
        access.antikytheramechanism$getFrameIndex().remove(source, owner);
        manager.setDirty();
        return true;
    }

    /** Active relocation ownership, excluding source coordinates that Create has already vacated. */
    public static boolean isActiveTransition(MechanismAssemblyManager manager, BlockPos position) {
        return access(manager).antikytheramechanism$getPendingContraptionMoves().values().stream()
                .anyMatch(move -> move.targetFrames().contains(position)
                        || (move.sourceFrames().contains(position) && !move.isSourceReleased(position)));
    }

    /**
     * Source-validation owner used only by Create finalization. A released source no longer has a
     * live {@code frameIndex} owner, so the historical owner is supplied for this one validation read.
     * If more than one placed journal claims the same released source, no owner is synthesized: the
     * transaction remains fail-closed rather than guessing which move the current loop is validating.
     */
    public static @Nullable UUID releasedHistoricalOwner(
            MechanismAssemblyManager manager,
            Map<?, ?> queriedMap,
            Object key,
            @Nullable Object actualValue) {
        MechanismAssemblyManagerAccessor access = access(manager);
        if (queriedMap != access.antikytheramechanism$getFrameIndex() || !(key instanceof BlockPos position)) {
            return actualValue instanceof UUID uuid ? uuid : null;
        }
        List<UUID> releasedOwners = access.antikytheramechanism$getPendingContraptionMoves().values().stream()
                .filter(PendingContraptionMove::hasPlacement)
                .filter(move -> move.isSourceReleased(position))
                .map(PendingContraptionMove::assemblyId)
                .distinct()
                .toList();
        if (releasedOwners.size() == 1) {
            return releasedOwners.getFirst();
        }
        return actualValue instanceof UUID uuid ? uuid : null;
    }

    /**
     * Rebuilds ownership at released coordinates after a failed placement rollback. Historical moving
     * owners are excluded. Exactly one remaining assembly may own the coordinate; ambiguous overlap is
     * recovery-locked rather than resolved by HashMap/save ordering.
     */
    public static void repairReleasedFrameIndex(MechanismAssemblyManager manager) {
        repairReleasedFrameIndex(manager, Set.of(), false);
    }

    /**
     * SavedData reconstructs {@code frameIndex} before Create journals are decoded, so a legitimate
     * released-source overlap initially looks corrupt and temporarily locks both assemblies. Repair the
     * index after journal decoding and remove only those locks proven to come solely from that resolved
     * overlap. Persisted/manual locks, evacuation locks, undecodable journals, duplicate UUIDs, empty
     * assemblies and any other unresolved overlap remain fail-closed.
     */
    public static void repairReleasedFrameIndexAfterLoad(
            MechanismAssemblyManager manager,
            CompoundTag persistedRoot) {
        MechanismAssemblyManagerAccessor access = access(manager);
        Set<UUID> protectedLocks = new HashSet<>();

        ListTag persistedLocks = persistedRoot.getList(CONTENT_RECOVERY_LOCKS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < persistedLocks.size(); index++) {
            CompoundTag lock = persistedLocks.getCompound(index);
            if (lock.hasUUID(ASSEMBLY_ID_TAG)) {
                protectedLocks.add(lock.getUUID(ASSEMBLY_ID_TAG));
            }
        }
        protectedLocks.addAll(access.antikytheramechanism$getPendingFrameEvacuations().keySet());

        Map<UUID, Integer> rawAssemblyIdCounts = new HashMap<>();
        ListTag rawAssemblies = persistedRoot.getList(ASSEMBLIES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < rawAssemblies.size(); index++) {
            CompoundTag raw = rawAssemblies.getCompound(index);
            if (raw.hasUUID(ASSEMBLY_RECORD_ID_TAG)) {
                rawAssemblyIdCounts.merge(raw.getUUID(ASSEMBLY_RECORD_ID_TAG), 1, Integer::sum);
            }
        }
        rawAssemblyIdCounts.forEach((id, count) -> {
            if (count > 1) {
                protectedLocks.add(id);
            }
        });

        access.antikytheramechanism$getAssemblies().values().stream()
                .filter(assembly -> assembly.frames().isEmpty())
                .map(MechanismAssembly::id)
                .forEach(protectedLocks::add);

        ListTag rawContraptions = persistedRoot.getList(PENDING_CONTRAPTION_MOVES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < rawContraptions.size(); index++) {
            CompoundTag raw = rawContraptions.getCompound(index);
            if (raw.hasUUID(ASSEMBLY_ID_TAG)) {
                UUID id = raw.getUUID(ASSEMBLY_ID_TAG);
                if (!access.antikytheramechanism$getPendingContraptionMoves().containsKey(id)) {
                    protectedLocks.add(id);
                }
            }
        }

        repairReleasedFrameIndex(manager, protectedLocks, true);
    }

    private static void repairReleasedFrameIndex(
            MechanismAssemblyManager manager,
            Set<UUID> externallyProtectedLocks,
            boolean clearResolvedLoadOverlapLocks) {
        MechanismAssemblyManagerAccessor access = access(manager);
        Map<UUID, MechanismAssembly> assemblies = access.antikytheramechanism$getAssemblies();
        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Map<UUID, PendingContraptionMove> pending = access.antikytheramechanism$getPendingContraptionMoves();
        Set<UUID> recoveryLocks = access.antikytheramechanism$getContentRecoveryLocks();

        Set<BlockPos> releasedPositions = pending.values().stream()
                .flatMap(move -> move.releasedSourceFrames().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (releasedPositions.isEmpty()) {
            return;
        }

        Set<UUID> protectedLocks = new HashSet<>(externallyProtectedLocks);
        Set<UUID> resolvedOverlapParticipants = new HashSet<>();
        Map<BlockPos, List<MechanismAssembly>> claimsByPosition = new HashMap<>();
        for (MechanismAssembly assembly : assemblies.values()) {
            for (BlockPos frame : assembly.frames()) {
                claimsByPosition.computeIfAbsent(frame, ignored -> new ArrayList<>()).add(assembly);
            }
        }
        for (Map.Entry<BlockPos, List<MechanismAssembly>> entry : claimsByPosition.entrySet()) {
            List<MechanismAssembly> claimants = entry.getValue();
            if (claimants.size() <= 1) {
                continue;
            }
            BlockPos position = entry.getKey();
            List<MechanismAssembly> historical = claimants.stream()
                    .filter(assembly -> {
                        PendingContraptionMove ownMove = pending.get(assembly.id());
                        return ownMove != null && ownMove.isSourceReleased(position);
                    })
                    .toList();
            long activeClaims = claimants.size() - historical.size();
            if (!historical.isEmpty() && activeClaims <= 1) {
                claimants.stream().map(MechanismAssembly::id).forEach(resolvedOverlapParticipants::add);
            } else {
                claimants.stream().map(MechanismAssembly::id).forEach(protectedLocks::add);
            }
        }

        boolean changed = false;
        for (BlockPos position : releasedPositions) {
            List<MechanismAssembly> candidates = new ArrayList<>();
            for (MechanismAssembly assembly : assemblies.values()) {
                if (!assembly.frames().contains(position)) {
                    continue;
                }
                PendingContraptionMove ownMove = pending.get(assembly.id());
                if (ownMove != null && ownMove.isSourceReleased(position)) {
                    continue;
                }
                candidates.add(assembly);
            }

            UUID previous = frameIndex.get(position);
            if (candidates.size() == 1) {
                UUID desired = candidates.getFirst().id();
                if (!desired.equals(previous)) {
                    frameIndex.put(position.immutable(), desired);
                    changed = true;
                }
                continue;
            }

            if (previous != null) {
                frameIndex.remove(position);
                changed = true;
            }
            if (candidates.size() > 1) {
                boolean newLock = false;
                for (MechanismAssembly candidate : candidates) {
                    newLock |= recoveryLocks.add(candidate.id());
                    protectedLocks.add(candidate.id());
                }
                for (PendingContraptionMove move : pending.values()) {
                    if (move.isSourceReleased(position)) {
                        newLock |= recoveryLocks.add(move.assemblyId());
                        protectedLocks.add(move.assemblyId());
                    }
                }
                if (newLock) {
                    changed = true;
                    AntikytheraMechanism.LOGGER.error(
                            "Recovery-locked ambiguous reused Create source {} because {} active assemblies claim it",
                            position,
                            candidates.size());
                }
            }
        }

        if (clearResolvedLoadOverlapLocks) {
            for (UUID id : resolvedOverlapParticipants) {
                if (!protectedLocks.contains(id) && recoveryLocks.remove(id)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            manager.setDirty();
        }
    }

    private static MechanismAssemblyManagerAccessor access(MechanismAssemblyManager manager) {
        return (MechanismAssemblyManagerAccessor) (Object) manager;
    }
}
