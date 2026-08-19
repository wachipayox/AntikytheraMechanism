package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
     * Source-validation owner used only by Create finalization. The returned UUID is historical: it
     * does not mutate {@code frameIndex} and therefore cannot hide a replacement Frame from target
     * collision checks or callbacks. The expected assembly comes from the active finalization loop,
     * so several nested in-flight moves may legitimately share the same historical source coordinate.
     */
    public static @Nullable UUID sourceValidationOwner(
            MechanismAssemblyManager manager,
            Map<?, ?> queriedMap,
            Object key,
            @Nullable Object actualValue,
            UUID expectedAssemblyId) {
        MechanismAssemblyManagerAccessor access = access(manager);
        if (queriedMap != access.antikytheramechanism$getFrameIndex() || !(key instanceof BlockPos position)) {
            return actualValue instanceof UUID uuid ? uuid : null;
        }
        PendingContraptionMove move =
                access.antikytheramechanism$getPendingContraptionMoves().get(expectedAssemblyId);
        if (move != null && move.hasPlacement() && move.isSourceReleased(position)) {
            return expectedAssemblyId;
        }
        return actualValue instanceof UUID uuid ? uuid : null;
    }

    /**
     * Rebuilds ownership at released coordinates after SavedData load or a failed placement rollback.
     * Historical moving owners are excluded. Exactly one remaining assembly may own the coordinate;
     * ambiguous overlap is recovery-locked rather than resolved by HashMap/save ordering.
     */
    public static void repairReleasedFrameIndex(MechanismAssemblyManager manager) {
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
                }
                for (PendingContraptionMove move : pending.values()) {
                    if (move.isSourceReleased(position)) {
                        newLock |= recoveryLocks.add(move.assemblyId());
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
        if (changed) {
            manager.setDirty();
        }
    }

    private static MechanismAssemblyManagerAccessor access(MechanismAssemblyManager manager) {
        return (MechanismAssemblyManagerAccessor) (Object) manager;
    }
}
