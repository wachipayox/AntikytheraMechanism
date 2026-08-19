package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.assembly.ContraptionSourceRelease;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Separates a Create journal's historical source coordinates from current physical Frame ownership.
 *
 * <p>Create keeps a captured assembly journaled at its original parent positions until disassembly.
 * Those coordinates are recovery metadata, not a reservation that should make an otherwise empty
 * world position unusable for minutes or hours. When a genuinely new Frame appears in such a vacated
 * source, this mixin releases only that source index while retaining the moving assembly and its
 * journal intact.</p>
 */
@Mixin(value = MechanismAssemblyManager.class, remap = false)
abstract class MechanismAssemblyManagerVacatedSourceMixin {
    @Unique
    private static final ThreadLocal<Map<UUID, Set<BlockPos>>>
            antikytheramechanism$releasedSourcesDuringLoad = new ThreadLocal<>();

    @Inject(method = "onFramePlaced", at = @At("HEAD"), remap = false)
    private void antikytheramechanism$releaseVacatedSourceBeforeRegistration(
            ServerLevel level,
            BlockPos framePos,
            CallbackInfoReturnable<MechanismAssembly> callback) {
        MechanismAssemblyManager self = (MechanismAssemblyManager) (Object) this;
        PendingContraptionMove move = ContraptionSourceRelease.vacatedSourceReservation(self, framePos);
        if (move == null) {
            return;
        }

        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) self;
        if (access.antikytheramechanism$getContentRecoveryLocks().contains(move.assemblyId())) {
            return;
        }
        if (!(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity frame)
                || frame.getAssemblyId() != null) {
            // A carried Frame restored by Create/Sable retains its assembly id in BE NBT. Only a
            // genuinely new, as-yet-unmapped Frame is allowed to take over a vacated source.
            return;
        }

        if (ContraptionSourceRelease.release(move, framePos)) {
            access.antikytheramechanism$getFrameIndex().remove(framePos, move.assemblyId());
            self.setDirty();
        }
    }

    /**
     * Run ordinary placement preflight against physical neighbours, not stale source-index entries
     * belonging to an extracted Create contraption.
     */
    @WrapMethod(method = "canPlaceFrame")
    private boolean antikytheramechanism$preflightVacatedSourceAsEmpty(
            ServerLevel level,
            BlockPos framePos,
            Operation<Boolean> original) {
        MechanismAssemblyManager self = (MechanismAssemblyManager) (Object) this;
        PendingContraptionMove reservation = ContraptionSourceRelease.vacatedSourceReservation(self, framePos);
        if (reservation == null) {
            return original.call(level, framePos);
        }

        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) self;
        if (access.antikytheramechanism$getContentRecoveryLocks().contains(reservation.assemblyId())) {
            return false;
        }

        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Map<BlockPos, UUID> hiddenVacatedSources = new LinkedHashMap<>();
        for (PendingContraptionMove move : access.antikytheramechanism$getPendingContraptionMoves().values()) {
            if (move.hasPlacement()) {
                continue;
            }
            for (BlockPos source : move.sourceFrames()) {
                UUID owner = frameIndex.get(source);
                if (!move.assemblyId().equals(owner)
                        || !level.hasChunkAt(source)
                        || level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get())) {
                    continue;
                }
                hiddenVacatedSources.put(source.immutable(), owner);
                frameIndex.remove(source, owner);
            }
        }

        try {
            return original.call(level, framePos);
        } finally {
            hiddenVacatedSources.forEach(frameIndex::putIfAbsent);
        }
    }

    /**
     * A source position stops being a physical relocation lock once its old owner explicitly released
     * it. Destination reservations remain positional because the moving Frame has not been indexed at
     * the destination yet when the low-level write occurs.
     */
    @WrapMethod(method = "isPhysicalRelocationTransition")
    private boolean antikytheramechanism$makeContraptionSourceLockOwnerAware(
            BlockPos framePosition,
            Operation<Boolean> original) {
        boolean transition = original.call(framePosition);
        if (!transition) {
            return false;
        }

        MechanismAssemblyManager self = (MechanismAssemblyManager) (Object) this;
        // Piston source/destination semantics are unchanged by this Create-only regression fix.
        if (self.isPistonLifecycleTransition(framePosition)) {
            return true;
        }

        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) self;
        UUID indexedOwner = access.antikytheramechanism$getFrameIndex().get(framePosition);
        for (PendingContraptionMove move : access.antikytheramechanism$getPendingContraptionMoves().values()) {
            if (move.hasPlacement() && move.targetFrames().contains(framePosition)) {
                return true;
            }
            if (move.sourceFrames().contains(framePosition)
                    && move.assemblyId().equals(indexedOwner)
                    && !ContraptionSourceRelease.isReleased(move, framePosition)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Once even one source was deliberately reused, a Frame found at the old coordinate is not proof
     * that Create collection never started. Suppress only the unstarted-capture shortcut; all normal
     * placement reconciliation below it remains untouched.
     */
    @WrapOperation(
            method = "reconcilePendingContraptionMoves",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/antikytheramechanism/assembly/PendingContraptionMove;hasPlacement()Z"))
    private boolean antikytheramechanism$releasedSourceProvesExtraction(
            PendingContraptionMove move,
            Operation<Boolean> original) {
        return original.call(move) || !ContraptionSourceRelease.releasedSources(move).isEmpty();
    }

    /**
     * The existing atomic finalizer requires source frameIndex ownership as a recovery precondition.
     * A released source can instead be owned by a replacement Frame. Temporarily present the historic
     * owner only to that synchronous transaction, then restore the exact replacement ownership after
     * success or failure. Target positions are never restored after a successful commit because the
     * placed moving assembly is authoritative there.
     */
    @WrapMethod(method = "finalizeContraptionPlacement")
    private boolean antikytheramechanism$finalizeWithReleasedSourceOwnership(
            ServerLevel level,
            Collection<UUID> assemblyIds,
            Operation<Boolean> original) {
        MechanismAssemblyManager self = (MechanismAssemblyManager) (Object) this;
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) self;
        Map<UUID, PendingContraptionMove> pending =
                access.antikytheramechanism$getPendingContraptionMoves();

        LinkedHashSet<UUID> requestedIds = new LinkedHashSet<>(assemblyIds);
        java.util.List<PendingContraptionMove> moves = requestedIds.stream()
                .map(pending::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (moves.stream().noneMatch(move -> !ContraptionSourceRelease.releasedSources(move).isEmpty())) {
            return original.call(level, assemblyIds);
        }

        Set<UUID> movingIds = Set.copyOf(requestedIds);
        Set<BlockPos> allTargets = new LinkedHashSet<>();
        moves.stream().filter(PendingContraptionMove::hasPlacement)
                .forEach(move -> allTargets.addAll(move.targetFrames()));

        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Map<BlockPos, IndexSnapshot> previousOwners = new LinkedHashMap<>();
        Map<BlockPos, UUID> requiredHistoricOwners = new LinkedHashMap<>();

        // Validate the complete temporary ownership overlay before mutating frameIndex. In particular,
        // never hide an unrelated real owner at a destination; the Create placement commit service is
        // responsible for evacuating that Frame first.
        for (PendingContraptionMove move : moves) {
            for (BlockPos rawSource : ContraptionSourceRelease.releasedSources(move)) {
                BlockPos source = rawSource.immutable();
                UUID previousRequirement = requiredHistoricOwners.putIfAbsent(source, move.assemblyId());
                if (previousRequirement != null && !previousRequirement.equals(move.assemblyId())) {
                    return false;
                }
                UUID currentOwner = frameIndex.get(source);
                if (allTargets.contains(source)
                        && currentOwner != null
                        && !movingIds.contains(currentOwner)) {
                    return original.call(level, assemblyIds);
                }
                previousOwners.putIfAbsent(
                        source,
                        new IndexSnapshot(frameIndex.containsKey(source), currentOwner));
            }
        }
        requiredHistoricOwners.forEach(frameIndex::put);

        boolean completed = false;
        try {
            completed = original.call(level, assemblyIds);
            return completed;
        } finally {
            if (!completed) {
                previousOwners.forEach((position, snapshot) -> snapshot.restore(frameIndex, position));
            } else {
                for (Map.Entry<BlockPos, IndexSnapshot> entry : previousOwners.entrySet()) {
                    BlockPos position = entry.getKey();
                    if (allTargets.contains(position)) {
                        // Successful target ownership written by the original finalizer wins.
                        continue;
                    }
                    IndexSnapshot snapshot = entry.getValue();
                    if (snapshot.present()
                            && snapshot.owner() != null
                            && !movingIds.contains(snapshot.owner())) {
                        frameIndex.put(position, snapshot.owner());
                        self.refreshFrame(level, position);
                    } else {
                        frameIndex.remove(position);
                    }
                }
            }
        }
    }

    /**
     * MechanismAssemblyManager currently rebuilds frameIndex before it decodes Create journals. Wrap
     * that load so the later Map.putIfAbsent hook already knows which historical source claims were
     * explicitly released. Invalid release metadata is ignored here and remains fail-closed in the
     * normal journal decoder.
     */
    @WrapMethod(method = "load")
    private static MechanismAssemblyManager antikytheramechanism$loadWithReleasedSourceContext(
            CompoundTag tag,
            HolderLookup.Provider registries,
            Operation<MechanismAssemblyManager> original) {
        Map<UUID, Set<BlockPos>> released = ContraptionSourceRelease.releasedSourcesByAssembly(tag);
        antikytheramechanism$releasedSourcesDuringLoad.set(released);
        try {
            return original.call(tag, registries);
        } finally {
            antikytheramechanism$releasedSourcesDuringLoad.remove();
        }
    }

    @WrapOperation(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object antikytheramechanism$skipReleasedHistoricalSourceIndex(
            Map<Object, Object> map,
            Object key,
            Object value,
            Operation<Object> original) {
        Map<UUID, Set<BlockPos>> released = antikytheramechanism$releasedSourcesDuringLoad.get();
        if (released != null
                && key instanceof BlockPos position
                && value instanceof UUID assemblyId
                && released.getOrDefault(assemblyId, Set.of()).contains(position)) {
            return null;
        }
        return original.call(map, key, value);
    }

    private record IndexSnapshot(boolean present, UUID owner) {
        private void restore(Map<BlockPos, UUID> frameIndex, BlockPos position) {
            if (present) {
                frameIndex.put(position, owner);
            } else {
                frameIndex.remove(position);
            }
        }
    }
}
