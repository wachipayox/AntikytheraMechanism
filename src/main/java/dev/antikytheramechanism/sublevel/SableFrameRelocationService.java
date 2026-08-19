package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateMiniKineticLifecycle;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bridges Sable's block-by-block assembly callbacks into Antikythera's crash-safe complete assembly
 * relocation journal.
 *
 * <p>Sable exposes a listener only once it is already entering its per-block copy loop. Waiting for
 * the first Frame callback to discover the rest of the move makes journal correctness depend on copy
 * order and prevents one assembly from knowing that another target owner is part of the same move.
 * Antikythera therefore partitions partial assemblies before Sable starts, then prepares every source
 * and destination journal together after Sable has allocated its destination plot chunks but before
 * the first destination block is written. Per-block callbacks merely verify the mapping observed by
 * Sable; they never create structural recovery metadata.</p>
 */
public final class SableFrameRelocationService {
    private static final Map<ServerLevel, Map<UUID, Relocation>> RELOCATIONS = new WeakHashMap<>();

    /** Package-private deterministic fault probe used only by Sable relocation GameTests. */
    private static volatile BeforeMoveFaultProbe beforeMoveFaultProbe;

    private SableFrameRelocationService() {
    }

    static AutoCloseable installBeforeMoveFaultProbe(BeforeMoveFaultProbe probe) {
        if (probe == null) {
            throw new NullPointerException("probe");
        }
        BeforeMoveFaultProbe previous = beforeMoveFaultProbe;
        beforeMoveFaultProbe = probe;
        return () -> beforeMoveFaultProbe = previous;
    }

    @FunctionalInterface
    interface BeforeMoveFaultProbe {
        void afterFrameBookkeeping(ServerLevel level, BlockPos source, BlockPos destination);
    }

    /**
     * Runs once for the complete source set before Sable enters its implementation. A host split can
     * move only some Frames of one Antikythera assembly; those Frames must first become a complete
     * logical assembly so the later all-destination preflight is well-defined.
     */
    public static boolean prepareMoveOperation(ServerLevel level, List<BlockPos> movedBlocks) {
        return MechanismAssemblyManager.get(level)
                .partitionPartialAssembliesForSableMove(level, movedBlocks);
    }

    /**
     * Persists complete relocation metadata for every Frame assembly selected by this one Sable move.
     *
     * <p>This hook runs from the first {@code setIgnoreOnPlace(..., true)} boundary in Sable 2.0.3
     * {@code moveBlocks}: Sable has already materialized/allocated any destination plot chunks, but it
     * has not read, copied, cleared or notified the first source block yet. Source journals are
     * prepared as one batch and destination journals as one batch, so a target owned by another
     * assembly participating in the same move is not mistaken for an unrelated collision.</p>
     */
    public static boolean prepareRelocationJournals(
            ServerLevel level,
            AssemblyTransform transform,
            List<BlockPos> movedBlocks) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<BlockPos> operationSources = movedBlocks.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Map<UUID, MechanismAssembly> movingAssemblies = new HashMap<>();
        for (BlockPos source : movedBlocks) {
            if (!level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            MechanismAssembly assembly = manager.getAssemblyAt(source).orElse(null);
            if (assembly == null) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable relocation preflight selected physical Frame {} with no assembly owner; refusing before the first block copy",
                        source);
                return false;
            }
            movingAssemblies.put(assembly.id(), assembly);
        }
        if (movingAssemblies.isEmpty()) {
            return true;
        }

        Map<UUID, Set<BlockPos>> sourceFramesByAssembly = new HashMap<>();
        Map<UUID, Map<BlockPos, BlockState>> carriedBoundaryByAssembly = new HashMap<>();
        Map<UUID, Set<BlockPos>> targetFramesByAssembly = new HashMap<>();
        Map<UUID, BlockPos> targetOrigins = new HashMap<>();
        Map<UUID, AssemblyPose> finalPoses = new HashMap<>();
        Map<UUID, Relocation> preparedRuntime = new HashMap<>();

        for (MechanismAssembly assembly : movingAssemblies.values()) {
            Set<BlockPos> sourceFrames = Set.copyOf(assembly.frames());
            if (!operationSources.containsAll(sourceFrames)) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable relocation preflight still contains only part of assembly {} after partition: move={}, frames={}",
                        assembly.id(), operationSources, sourceFrames);
                return false;
            }

            BlockPos sourceOrigin = assembly.origin();
            BlockPos targetOrigin = transform.apply(sourceOrigin).immutable();
            BlockPos delta = targetOrigin.subtract(sourceOrigin).immutable();
            Set<BlockPos> targets = sourceFrames.stream()
                    .map(source -> transform.apply(source).immutable())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean translationOnly = sourceFrames.stream().allMatch(source ->
                    transform.apply(source).equals(source.offset(delta)));
            if (!translationOnly) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable attempted a non-translation Frame relocation for assembly {}; refusing before the first block copy",
                        assembly.id());
                return false;
            }

            MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(level, targetOrigin);
            boolean oneUsableHost = targetHost.allowed()
                    && targets.stream().allMatch(target ->
                            MechanismAssemblyHost.sameResolvedHost(level, targetOrigin, target));
            if (!oneUsableHost) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable attempted to move assembly {} into unsupported or mixed host space {} before the first block copy",
                        assembly.id(), targetHost.kind());
                return false;
            }

            AssemblyPose finalLocalPose = assembly.poseTarget().translated(
                    new Vector3d(delta.getX(), delta.getY(), delta.getZ()));
            Relocation relocation = new Relocation(
                    assembly.id(), sourceFrames, sourceOrigin, assembly.poseTarget());
            relocation.prepare(delta, targetOrigin);

            sourceFramesByAssembly.put(assembly.id(), sourceFrames);
            carriedBoundaryByAssembly.put(
                    assembly.id(), carriedBoundarySnapshot(level, sourceFrames));
            targetFramesByAssembly.put(assembly.id(), targets);
            targetOrigins.put(assembly.id(), targetOrigin);
            finalPoses.put(assembly.id(), finalLocalPose);
            preparedRuntime.put(assembly.id(), relocation);
        }

        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> active = RELOCATIONS.get(level);
            if (active != null
                    && preparedRuntime.keySet().stream().anyMatch(active::containsKey)) {
                AntikytheraMechanism.LOGGER.error(
                        "Refusing nested/concurrent Sable relocation for assemblies {}",
                        preparedRuntime.keySet());
                return false;
            }
        }

        boolean sourceJournaled = manager.prepareContraptionMoves(
                level,
                sourceFramesByAssembly,
                carriedBoundaryByAssembly,
                BlockPos.ZERO,
                true);
        if (!sourceJournaled) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not journal complete Sable sources {} before the first block copy",
                    preparedRuntime.keySet());
            return false;
        }

        boolean destinationJournaled = manager.prepareContraptionPlacement(
                level,
                targetFramesByAssembly,
                targetOrigins,
                finalPoses);
        if (!destinationJournaled) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not journal complete Sable destinations {} before the first block copy; source journals remain fail-closed for recovery",
                    preparedRuntime.keySet());
            return false;
        }

        synchronized (RELOCATIONS) {
            RELOCATIONS.computeIfAbsent(level, ignored -> new HashMap<>())
                    .putAll(preparedRuntime);
        }

        // Sable weighs destination blocks during the copy loop and source blocks again when clearing
        // the old structure. Freeze the authoritative pre-relocation Frame+payload mass now, while
        // every logical Frame -> mini-cell mapping still points at the coherent source topology.
        for (MechanismAssembly assembly : movingAssemblies.values()) {
            Relocation relocation = preparedRuntime.get(assembly.id());
            for (BlockPos sourceFrame : relocation.sourceFrames()) {
                SableAssemblyMoveContext.freezeFrameMass(
                        level,
                        sourceFrame,
                        ManagedFrameMassPolicy.snapshotEffectiveFrameMass(level, assembly, sourceFrame));
            }
        }
        return true;
    }

    public static void beforeMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockPos oldPosition,
            BlockPos newPosition) {
        if (originLevel != resultingLevel) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(originLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(oldPosition).orElse(null);
        if (assembly == null) {
            return;
        }

        Relocation relocation;
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(originLevel);
            relocation = byAssembly == null ? null : byAssembly.get(assembly.id());
            if (relocation != null) {
                relocation.record(oldPosition, newPosition);
            }
        }

        if (relocation == null) {
            // prepareRelocationJournals runs outside Sable's per-block exception handler and is the
            // only place allowed to create recovery metadata. Reaching a Frame callback without that
            // preflight means continuing would reproduce the historical half-journalled move.
            AntikytheraMechanism.LOGGER.error(
                    "Sable reached moving frame {} for assembly {} without a complete preflight journal",
                    oldPosition, assembly.id());
            return;
        }

        // Deterministic test seam at the exact historical leak point: all Antikythera beforeMove
        // bookkeeping has completed, but Sable has not yet copied the Frame or invoked afterMove.
        // A throwing probe is intentionally allowed to escape into Sable's per-block catch.
        BeforeMoveFaultProbe probe = beforeMoveFaultProbe;
        if (probe != null) {
            probe.afterFrameBookkeeping(originLevel, oldPosition, newPosition);
        }
    }

    public static void afterMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockPos oldPosition,
            BlockPos newPosition) {
        if (originLevel != resultingLevel) {
            return;
        }

        Relocation relocation;
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(originLevel);
            if (byAssembly == null) {
                return;
            }
            relocation = byAssembly.values().stream()
                    .filter(candidate -> candidate.sourceFrames().contains(oldPosition))
                    .findFirst()
                    .orElse(null);
        }
        if (relocation != null) {
            // Do not finalize here. Sable invokes the listener per block, so the last Frame may be
            // followed by a supporting macro block from the same move. The complete-operation mixin
            // calls finishMoveOperation only after every destination write, neighbour notification
            // and source removal in SubLevelAssemblyHelper.moveBlocks has finished.
            relocation.record(oldPosition, newPosition);
        }
    }

    /** Commits relocations belonging to the current successful Sable moveBlocks operation. */
    public static void finishMoveOperation(ServerLevel level) {
        Set<BlockPos> operationSources = SableAssemblyMoveContext.sourceBlocks(level);
        if (operationSources.isEmpty()) {
            return;
        }

        List<Relocation> candidates;
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(level);
            if (byAssembly == null || byAssembly.isEmpty()) {
                return;
            }
            candidates = byAssembly.values().stream()
                    .filter(candidate -> operationSources.containsAll(candidate.sourceFrames()))
                    .toList();
        }
        if (candidates.isEmpty()) {
            return;
        }

        List<Relocation> ready = new ArrayList<>();
        for (Relocation relocation : candidates) {
            BlockPos actualDelta = relocation.uniformTranslation();
            if (actualDelta == null
                    || relocation.preparedDelta() == null
                    || !actualDelta.equals(relocation.preparedDelta())) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable completed moveBlocks for assembly {} with an incomplete or different Frame mapping; retaining its persisted relocation journal for recovery",
                        relocation.assemblyId());
                forgetRuntimeMapping(level, relocation.assemblyId());
                continue;
            }
            ready.add(relocation);
        }
        if (ready.isEmpty()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<UUID> readyIds = ready.stream().map(Relocation::assemblyId).toList();
        boolean finalized = manager.finalizeContraptionPlacement(level, readyIds);
        if (!finalized) {
            for (Relocation relocation : ready) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not finalize Sable relocation for assembly {}; persisted journal remains authoritative",
                        relocation.assemblyId());
                forgetRuntimeMapping(level, relocation.assemblyId());
            }
            return;
        }

        // The managed child and its Create BlockEntities stay in the same Sable plot while only the
        // outer Frames move. Re-advertise now that the new physical topology is authoritative so a
        // relocated assembly can join an existing network or become a new bridge. This is a strict
        // no-op when Create is absent.
        CreateMiniKineticLifecycle.scheduleAfterPhysicalRelocation(level, readyIds);

        for (Relocation relocation : ready) {
            MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(
                    level, relocation.targetOrigin());
            BlockPos actualDelta = relocation.uniformTranslation();
            forgetRuntimeMapping(level, relocation.assemblyId());
            AntikytheraMechanism.LOGGER.debug(
                    "Adopted complete Sable relocation for assembly {} by {} into host {}",
                    relocation.assemblyId(),
                    actualDelta,
                    targetHost.kind());
        }
    }

    /**
     * Captures only parent blocks that are both face-adjacent to a Frame and part of this exact Sable
     * move. Stationary world neighbours are deliberately excluded: during relocation the managed
     * child may rely on carried structure, never on a stale block that Sable left behind.
     */
    private static Map<BlockPos, BlockState> carriedBoundarySnapshot(
            ServerLevel level,
            Set<BlockPos> sourceFrames) {
        Set<BlockPos> movedBlocks = SableAssemblyMoveContext.sourceBlocks(level);
        if (movedBlocks.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (BlockPos frame : sourceFrames) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = frame.relative(direction);
                if (!movedBlocks.contains(neighbor)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbor);
                if (state.isAir() || state.is(ModRegistries.MECHANISM_FRAME.get())) {
                    continue;
                }
                result.put(neighbor.immutable(), state);
            }
        }
        return Map.copyOf(result);
    }

    private static void forgetRuntimeMapping(ServerLevel level, UUID assemblyId) {
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(level);
            if (byAssembly == null) {
                return;
            }
            byAssembly.remove(assemblyId);
            if (byAssembly.isEmpty()) {
                RELOCATIONS.remove(level);
            }
        }
    }

    private static final class Relocation {
        private final UUID assemblyId;
        private final Set<BlockPos> sourceFrames;
        private final BlockPos sourceOrigin;
        private final AssemblyPose startPose;
        private final Map<BlockPos, BlockPos> destinations = new HashMap<>();
        private boolean invalidMapping;
        private BlockPos preparedDelta;
        private BlockPos targetOrigin;

        private Relocation(
                UUID assemblyId,
                Set<BlockPos> sourceFrames,
                BlockPos sourceOrigin,
                AssemblyPose startPose) {
            this.assemblyId = assemblyId;
            this.sourceFrames = sourceFrames;
            this.sourceOrigin = sourceOrigin.immutable();
            this.startPose = startPose;
        }

        void record(BlockPos source, BlockPos destination) {
            if (!sourceFrames.contains(source)) {
                return;
            }
            BlockPos previous = destinations.put(source.immutable(), destination.immutable());
            if (previous != null && !previous.equals(destination)) {
                invalidMapping = true;
            }
        }

        void prepare(BlockPos delta, BlockPos targetOrigin) {
            this.preparedDelta = delta.immutable();
            this.targetOrigin = targetOrigin.immutable();
        }

        boolean complete() {
            return !invalidMapping && destinations.keySet().containsAll(sourceFrames);
        }

        BlockPos uniformTranslation() {
            if (!complete()) {
                return null;
            }
            BlockPos expected = null;
            for (BlockPos source : sourceFrames) {
                BlockPos destination = destinations.get(source);
                BlockPos delta = destination.subtract(source);
                if (expected == null) {
                    expected = delta.immutable();
                } else if (!expected.equals(delta)) {
                    return null;
                }
            }
            return expected;
        }

        UUID assemblyId() {
            return assemblyId;
        }

        Set<BlockPos> sourceFrames() {
            return sourceFrames;
        }

        BlockPos sourceOrigin() {
            return sourceOrigin;
        }

        AssemblyPose startPose() {
            return startPose;
        }

        BlockPos preparedDelta() {
            return preparedDelta;
        }

        BlockPos targetOrigin() {
            return targetOrigin;
        }
    }
}
