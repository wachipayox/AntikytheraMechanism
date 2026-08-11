package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Authoritative, process-local reservations for implementation blocks outside an assembly's
 * FrameMask. Persistent integrations rebuild these reservations from their own SavedData.
 */
public final class ServiceShellReservations {
    private static final Object LOCK = new Object();
    private static final Map<ServerLevel, Map<ReservationKey, ServiceShellReservation>> BY_LEVEL =
            new WeakHashMap<>();
    private static final Set<ResourceLocation> INTERNAL_BLOCK_IDS = new HashSet<>();
    private static final int INTERNAL_UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private ServiceShellReservations() {
    }

    /** Marks a no-item implementation block as service-shell-only. Idempotent by registry id. */
    public static void registerInternalBlock(ResourceLocation blockId) {
        Objects.requireNonNull(blockId, "blockId");
        synchronized (LOCK) {
            INTERNAL_BLOCK_IDS.add(blockId);
        }
    }

    public static boolean isInternalBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        synchronized (LOCK) {
            return INTERNAL_BLOCK_IDS.contains(id);
        }
    }

    /**
     * Atomically reserves every requested local position. This method never loads a chunk and never
     * overwrites a foreign block. Existing identical reservations and endpoints are accepted.
     */
    public static BatchResult reserveBatch(
            ServerLevel level,
            MechanismAssembly assembly,
            Collection<ServiceShellReservation> requested) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        List<ServiceShellReservation> reservations = List.copyOf(requested);
        if (reservations.isEmpty()) {
            return BatchResult.success();
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null
                || !MechanismSubLevelService.ensureServiceAnchorSafe(level, assembly, subLevel)) {
            return BatchResult.unavailable();
        }

        Set<ReservationKey> requestedKeys = new HashSet<>();
        for (ServiceShellReservation reservation : reservations) {
            if (!assembly.id().equals(reservation.assemblyId())) {
                throw new IllegalArgumentException("Reservation belongs to another assembly");
            }
            ReservationKey key = ReservationKey.of(reservation);
            if (!requestedKeys.add(key)) {
                return BatchResult.conflict(reservation);
            }
            if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, reservation.miniPosition())
                    || reservation.miniPosition().equals(assembly.serviceAnchor())
                    || !MechanismSubLevelService.canAddressMiniPosition(
                            level, subLevel, reservation.miniPosition())) {
                return BatchResult.conflict(reservation);
            }

            BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, reservation.miniPosition());
            if (!level.hasChunkAt(global)) {
                return BatchResult.unavailable();
            }
            BlockState actual = level.getBlockState(global);
            if (!actual.isAir() && !reservation.expectedBlockId().equals(blockId(actual))) {
                return BatchResult.conflict(reservation);
            }
        }

        synchronized (LOCK) {
            Map<ReservationKey, ServiceShellReservation> active =
                    BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
            for (ServiceShellReservation reservation : reservations) {
                ServiceShellReservation existing = active.get(ReservationKey.of(reservation));
                if (existing != null && !existing.equals(reservation)) {
                    return BatchResult.conflict(reservation);
                }
            }
            reservations.forEach(reservation -> active.put(ReservationKey.of(reservation), reservation));
        }
        return BatchResult.success();
    }

    public static ServiceShellReservation find(
            ServerLevel level,
            UUID assemblyId,
            BlockPos miniPosition) {
        synchronized (LOCK) {
            Map<ReservationKey, ServiceShellReservation> active = BY_LEVEL.get(level);
            return active == null
                    ? null
                    : active.get(new ReservationKey(assemblyId, miniPosition));
        }
    }

    public static boolean isActive(ServerLevel level, ServiceShellReservation reservation) {
        return reservation.equals(find(level, reservation.assemblyId(), reservation.miniPosition()));
    }

    /** Places or reconciles the expected block without touching a foreign occupant. */
    public static OperationResult install(
            ServerLevel level,
            MechanismAssembly assembly,
            ServiceShellReservation reservation,
            BlockState expectedState) {
        Objects.requireNonNull(expectedState, "expectedState");
        if (!reservation.expectedBlockId().equals(blockId(expectedState))) {
            throw new IllegalArgumentException("BlockState does not match the reserved internal block id");
        }
        ResolvedReservation resolved = resolveLoaded(level, assembly, reservation);
        if (resolved.result() != OperationResult.SUCCESS) {
            return resolved.result();
        }

        BlockState actual = level.getBlockState(resolved.globalPosition());
        if (!actual.isAir() && !reservation.expectedBlockId().equals(blockId(actual))) {
            return OperationResult.CONFLICT;
        }
        if (!actual.equals(expectedState)) {
            boolean wrote = FrameMaskWriteGuard.getServiceShellBypassing(
                    level,
                    reservation,
                    () -> level.setBlock(resolved.globalPosition(), expectedState, INTERNAL_UPDATE_FLAGS));
            if (!wrote && !level.getBlockState(resolved.globalPosition()).equals(expectedState)) {
                return OperationResult.FAILED;
            }
        }
        return level.getBlockState(resolved.globalPosition()).equals(expectedState)
                ? OperationResult.SUCCESS
                : OperationResult.FAILED;
    }

    /**
     * Runs metadata initialization only while the exact reserved endpoint is loaded and present.
     * The callback can validate/write nonce, box position and port index on its BlockEntity.
     */
    public static OperationResult configure(
            ServerLevel level,
            MechanismAssembly assembly,
            ServiceShellReservation reservation,
            Consumer<BlockEntity> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        ResolvedReservation resolved = resolveLoaded(level, assembly, reservation);
        if (resolved.result() != OperationResult.SUCCESS) {
            return resolved.result();
        }
        BlockState state = level.getBlockState(resolved.globalPosition());
        if (!reservation.expectedBlockId().equals(blockId(state))) {
            return state.isAir() ? OperationResult.FAILED : OperationResult.CONFLICT;
        }
        BlockEntity blockEntity = level.getBlockEntity(resolved.globalPosition());
        if (blockEntity == null) {
            return OperationResult.FAILED;
        }
        try {
            initializer.accept(blockEntity);
            blockEntity.setChanged();
            return OperationResult.SUCCESS;
        } catch (RuntimeException | LinkageError exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not configure reserved service-shell block for assembly {} at {}",
                    assembly.id(),
                    reservation.miniPosition(),
                    exception);
            return OperationResult.FAILED;
        }
    }

    /** Removes the exact endpoint and releases its reservation; air is an idempotent success. */
    public static OperationResult retire(
            ServerLevel level,
            MechanismAssembly assembly,
            ServiceShellReservation reservation,
            Runnable beforeRemoval) {
        Objects.requireNonNull(beforeRemoval, "beforeRemoval");
        ResolvedReservation resolved = resolveLoaded(level, assembly, reservation);
        if (resolved.result() != OperationResult.SUCCESS) {
            return resolved.result();
        }
        BlockState actual = level.getBlockState(resolved.globalPosition());
        if (!actual.isAir() && !reservation.expectedBlockId().equals(blockId(actual))) {
            return OperationResult.CONFLICT;
        }
        if (!actual.isAir()) {
            try {
                beforeRemoval.run();
            } catch (RuntimeException | LinkageError exception) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not prepare reserved service-shell block removal for assembly {} at {}",
                        assembly.id(),
                        reservation.miniPosition(),
                        exception);
                return OperationResult.FAILED;
            }
            FrameMaskWriteGuard.getServiceShellBypassing(
                    level,
                    reservation,
                    () -> level.setBlock(
                            resolved.globalPosition(),
                            Blocks.AIR.defaultBlockState(),
                            INTERNAL_UPDATE_FLAGS));
            if (!level.getBlockState(resolved.globalPosition()).isAir()
                    || level.getBlockEntity(resolved.globalPosition()) != null) {
                return OperationResult.FAILED;
            }
        }
        return release(level, reservation) ? OperationResult.SUCCESS : OperationResult.CONFLICT;
    }

    public static OperationResult retire(
            ServerLevel level,
            MechanismAssembly assembly,
            ServiceShellReservation reservation) {
        return retire(level, assembly, reservation, () -> {
        });
    }

    /** Idempotent only for an absent or exactly matching owner. */
    public static boolean release(ServerLevel level, ServiceShellReservation reservation) {
        synchronized (LOCK) {
            Map<ReservationKey, ServiceShellReservation> active = BY_LEVEL.get(level);
            if (active == null) {
                return true;
            }
            ReservationKey key = ReservationKey.of(reservation);
            ServiceShellReservation existing = active.get(key);
            if (existing == null) {
                return true;
            }
            if (!existing.equals(reservation)) {
                return false;
            }
            active.remove(key);
            if (active.isEmpty()) {
                BY_LEVEL.remove(level);
            }
            return true;
        }
    }

    public static boolean releaseBatch(
            ServerLevel level,
            Collection<ServiceShellReservation> reservations) {
        boolean success = true;
        List<ServiceShellReservation> reverse = new ArrayList<>(reservations);
        java.util.Collections.reverse(reverse);
        for (ServiceShellReservation reservation : reverse) {
            success &= release(level, reservation);
        }
        return success;
    }

    private static ResolvedReservation resolveLoaded(
            ServerLevel level,
            MechanismAssembly assembly,
            ServiceShellReservation reservation) {
        if (!assembly.id().equals(reservation.assemblyId()) || !isActive(level, reservation)) {
            return ResolvedReservation.conflict();
        }
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            return ResolvedReservation.unavailable();
        }
        BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, reservation.miniPosition());
        if (!level.hasChunkAt(global)) {
            return ResolvedReservation.unavailable();
        }
        return new ResolvedReservation(OperationResult.SUCCESS, global);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    public enum BatchStatus {
        SUCCESS,
        UNAVAILABLE,
        CONFLICT
    }

    public record BatchResult(BatchStatus status, ServiceShellReservation conflict) {
        public static BatchResult success() {
            return new BatchResult(BatchStatus.SUCCESS, null);
        }

        public static BatchResult unavailable() {
            return new BatchResult(BatchStatus.UNAVAILABLE, null);
        }

        public static BatchResult conflict(ServiceShellReservation conflict) {
            return new BatchResult(BatchStatus.CONFLICT, conflict);
        }

        public boolean succeeded() {
            return status == BatchStatus.SUCCESS;
        }
    }

    public enum OperationResult {
        SUCCESS,
        UNAVAILABLE,
        CONFLICT,
        FAILED
    }

    private record ReservationKey(UUID assemblyId, BlockPos miniPosition) {
        private ReservationKey {
            miniPosition = miniPosition.immutable();
        }

        private static ReservationKey of(ServiceShellReservation reservation) {
            return new ReservationKey(reservation.assemblyId(), reservation.miniPosition());
        }
    }

    private record ResolvedReservation(OperationResult result, BlockPos globalPosition) {
        private static ResolvedReservation unavailable() {
            return new ResolvedReservation(OperationResult.UNAVAILABLE, null);
        }

        private static ResolvedReservation conflict() {
            return new ResolvedReservation(OperationResult.CONFLICT, null);
        }
    }
}
