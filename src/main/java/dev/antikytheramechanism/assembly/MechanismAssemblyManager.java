package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.frame.FrameEvacuationService;
import dev.antikytheramechanism.frame.PendingFrameEvacuation;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.joml.Quaterniond;

public final class MechanismAssemblyManager extends SavedData {
    private static final String DATA_NAME = AntikytheraMechanism.MOD_ID + "_assemblies";
    private static final String ASSEMBLIES_TAG = "assemblies";
    private static final String PENDING_PISTON_MOVES_TAG = "pending_piston_moves";
    private static final String PENDING_CONTRAPTION_MOVES_TAG = "pending_contraption_moves";
    private static final String PENDING_FRAME_EVACUATIONS_TAG = "pending_frame_evacuations";
    private static final String CONTENT_RECOVERY_LOCKS_TAG = "content_recovery_locks";
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final SavedData.Factory<MechanismAssemblyManager> FACTORY =
            new SavedData.Factory<>(MechanismAssemblyManager::new, MechanismAssemblyManager::load);

    private final Map<UUID, MechanismAssembly> assemblies = new HashMap<>();
    private final Map<BlockPos, UUID> frameIndex = new HashMap<>();
    private final Map<UUID, PendingPistonMove> pendingPistonMoves = new HashMap<>();
    private final Map<UUID, PendingContraptionMove> pendingContraptionMoves = new HashMap<>();
    private final List<CompoundTag> undecodedContraptionJournals = new ArrayList<>();
    private final Map<UUID, PendingFrameEvacuation> pendingFrameEvacuations = new HashMap<>();
    private final List<CompoundTag> undecodedFrameEvacuationJournals = new ArrayList<>();
    private final Set<UUID> contentRecoveryLocks = new java.util.HashSet<>();
    private final Set<UUID> invalidPistonMovesLogged = new java.util.HashSet<>();
    private final Set<UUID> invalidContraptionMovesLogged = new java.util.HashSet<>();
    private final Set<BlockPos> evacuatingFrames = new java.util.HashSet<>();
    private final Set<BlockPos> evacuatedFrames = new java.util.HashSet<>();
    private long lastMaintenanceTick = Long.MIN_VALUE;

    /** Package-private deterministic fault probe used only by in-game transaction tests. */
    private static volatile ContraptionCommitProbe contraptionCommitProbe = (assemblyId, frame, ordinal) -> {};

    static AutoCloseable installContraptionCommitProbe(ContraptionCommitProbe probe) {
        ContraptionCommitProbe previous = contraptionCommitProbe;
        contraptionCommitProbe = java.util.Objects.requireNonNull(probe, "probe");
        return () -> contraptionCommitProbe = previous;
    }

    @FunctionalInterface
    interface ContraptionCommitProbe {
        void afterFrameSynchronized(UUID assemblyId, BlockPos frame, int ordinal);
    }

    public static MechanismAssemblyManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Collection<MechanismAssembly> assemblies() {
        return java.util.Collections.unmodifiableCollection(assemblies.values());
    }

    public Optional<MechanismAssembly> getAssembly(UUID id) {
        return Optional.ofNullable(assemblies.get(id));
    }

    public Optional<MechanismAssembly> getAssemblyAt(BlockPos framePos) {
        UUID id = frameIndex.get(framePos);
        return id == null ? Optional.empty() : getAssembly(id);
    }

    /** Read-only preflight used by the low-level block-write guard before a frame is placed. */
    public boolean canPlaceFrame(ServerLevel level, BlockPos framePos) {
        if (frameIndex.containsKey(framePos) || isPhysicalRelocationTransition(framePos)) {
            return true;
        }

        FrameOrientation placementOrientation = placementOrientation(level, framePos);
        Map<UUID, MechanismAssembly> neighbors = compatibleAdjacentAssemblies(framePos, placementOrientation);
        if (neighbors.isEmpty()) {
            return true;
        }
        if (neighbors.keySet().stream().anyMatch(id ->
                pendingPistonMoves.containsKey(id)
                        || pendingContraptionMoves.containsKey(id)
                        || contentRecoveryLocks.contains(id))) {
            return false;
        }

        MechanismAssembly target = neighbors.values().stream()
                .min(assemblySurvivorOrder())
                .orElseThrow();
        ServerSubLevel targetSubLevel = MechanismSubLevelService.findExisting(level, target);
        if (targetSubLevel == null && target.subLevelId() != null) {
            return false;
        }
        if (targetSubLevel != null
                && !MechanismSubLevelService.canAddressFrame(level, targetSubLevel, target, framePos)) {
            return false;
        }

        for (MechanismAssembly neighbor : neighbors.values()) {
            if (!AssemblyOrientationMath.compatiblePhysical(target, neighbor, 1.0E-6)) {
                return false;
            }
            if (targetSubLevel != null) {
                for (BlockPos neighborFrame : neighbor.frames()) {
                    if (!MechanismSubLevelService.canAddressFrame(
                            level, targetSubLevel, target, neighborFrame)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Updates only the world transform; mini BlockPos and BlockStates remain unchanged. */
    public boolean updatePoseTarget(UUID assemblyId, AssemblyPose target) {
        MechanismAssembly assembly = assemblies.get(assemblyId);
        if (assembly == null || contentRecoveryLocks.contains(assemblyId)) {
            return false;
        }
        if (!assembly.poseTarget().approximatelyEquals(target, 1.0E-10)) {
            assembly.setPoseTarget(target);
            setDirty();
        }
        return true;
    }

    public Optional<PendingPistonMove> pendingPistonMove(UUID assemblyId) {
        return Optional.ofNullable(pendingPistonMoves.get(assemblyId));
    }

    public Optional<PendingContraptionMove> pendingContraptionMove(UUID assemblyId) {
        return Optional.ofNullable(pendingContraptionMoves.get(assemblyId));
    }

    public Optional<PendingFrameEvacuation> pendingFrameEvacuation(UUID assemblyId) {
        return Optional.ofNullable(pendingFrameEvacuations.get(assemblyId));
    }

    public boolean isContentRecoveryLocked(UUID assemblyId) {
        return contentRecoveryLocks.contains(assemblyId);
    }

    /**
     * Read-only Create collection preflight. A complete assembly may be translated or yaw-rotated;
     * the persisted journal keeps the physical source layout separate from immutable logical offsets.
     */
    public boolean canCaptureContraption(
            ServerLevel level,
            Map<UUID, ? extends Collection<BlockPos>> capturedLocalFrames,
            boolean translationOnly) {
        for (Map.Entry<UUID, ? extends Collection<BlockPos>> entry : capturedLocalFrames.entrySet()) {
            MechanismAssembly assembly = assemblies.get(entry.getKey());
            if (assembly == null
                    || pendingPistonMoves.containsKey(assembly.id())
                    || pendingContraptionMoves.containsKey(assembly.id())
                    || contentRecoveryLocks.contains(assembly.id())
                    || PendingContraptionMove.findTranslation(entry.getValue(), assembly.frames()).isEmpty()) {
                return false;
            }
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null && assembly.subLevelId() != null) {
                return false;
            }
            if (subLevel != null && subLevel.isRemoved()) {
                return false;
            }
            for (BlockPos frame : assembly.frames()) {
                if (!level.hasChunkAt(frame)
                        || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())
                        || !assembly.id().equals(frameIndex.get(frame))
                        || !(level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity frameBlockEntity)
                        || !assembly.id().equals(frameBlockEntity.getAssemblyId())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Journals a fully collected Create contraption immediately before extraction. */
    public boolean prepareContraptionMoves(
            ServerLevel level,
            Map<UUID, ? extends Collection<BlockPos>> capturedLocalFrames,
            BlockPos sourceTranslation,
            boolean translationOnly) {
        return prepareContraptionMoves(
                level, capturedLocalFrames, Map.of(), sourceTranslation, translationOnly);
    }

    /**
     * Journals a Create capture together with the non-Frame blocks physically carried beside each
     * Frame. Those frozen states are structural boundary snapshots only: redstone/transmission bridges
     * remain quiesced for the lifetime of the journal.
     */
    public boolean prepareContraptionMoves(
            ServerLevel level,
            Map<UUID, ? extends Collection<BlockPos>> capturedLocalFrames,
            Map<UUID, ? extends Map<BlockPos, BlockState>> carriedBoundaryBlocksByAssembly,
            BlockPos sourceTranslation,
            boolean translationOnly) {
        if (!canCaptureContraption(level, capturedLocalFrames, translationOnly)) {
            return false;
        }
        if (!carriedBoundaryBlocksByAssembly.isEmpty()
                && !capturedLocalFrames.keySet().containsAll(carriedBoundaryBlocksByAssembly.keySet())) {
            return false;
        }
        List<PendingContraptionMove> prepared = new ArrayList<>();
        for (Map.Entry<UUID, ? extends Collection<BlockPos>> entry : capturedLocalFrames.entrySet()) {
            MechanismAssembly assembly = assemblies.get(entry.getKey());
            Set<BlockPos> sources = entry.getValue().stream()
                    .map(local -> local.offset(sourceTranslation).immutable())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!sources.equals(assembly.frames())) {
                return false;
            }
            Map<BlockPos, BlockState> carriedBoundaryBlocks = new HashMap<>();
            Map<BlockPos, BlockState> supplied = carriedBoundaryBlocksByAssembly.get(entry.getKey());
            if (supplied != null) {
                carriedBoundaryBlocks.putAll(supplied);
            }
            prepared.add(new PendingContraptionMove(
                    assembly.id(),
                    sources,
                    assembly.origin(),
                    entry.getValue(),
                    assembly.poseTarget(),
                    level.getGameTime(),
                    carriedBoundaryBlocks));
        }
        prepared.forEach(move -> pendingContraptionMoves.put(move.assemblyId(), move));
        if (!prepared.isEmpty()) {
            setDirty();
        }
        return true;
    }

    /** Frozen structural boundary state while Create has removed the real parent blocks. */
    public Optional<BlockState> pendingContraptionBoundaryState(UUID assemblyId, BlockPos sourceParentPosition) {
        PendingContraptionMove move = pendingContraptionMoves.get(assemblyId);
        return move == null ? Optional.empty() : move.carriedBoundaryStateAtSource(sourceParentPosition);
    }

    /** Persists snapped destinations before Create starts placing any frame. */
    public boolean prepareContraptionPlacement(
            ServerLevel level,
            Map<UUID, ? extends Collection<BlockPos>> targetFrames,
            Map<UUID, BlockPos> targetOrigins,
            Map<UUID, AssemblyPose> finalPoses) {
        if (!targetFrames.keySet().equals(targetOrigins.keySet())
                || !targetFrames.keySet().equals(finalPoses.keySet())) {
            return false;
        }
        Map<UUID, PendingContraptionMove> replacements = new HashMap<>();
        Set<BlockPos> allTargets = new java.util.HashSet<>();
        for (Map.Entry<UUID, ? extends Collection<BlockPos>> entry : targetFrames.entrySet()) {
            PendingContraptionMove move = pendingContraptionMoves.get(entry.getKey());
            if (move == null || move.hasPlacement()) {
                return false;
            }
            PendingContraptionMove placed;
            try {
                placed = move.withPlacement(
                        entry.getValue(),
                        targetOrigins.get(entry.getKey()),
                        finalPoses.get(entry.getKey()));
            } catch (IllegalArgumentException exception) {
                AntikytheraMechanism.LOGGER.error(
                        "Rejected unsupported Create placement for assembly {}: {}",
                        entry.getKey(),
                        exception.getMessage());
                return false;
            }
            for (BlockPos target : placed.targetFrames()) {
                if (!allTargets.add(target) || !level.hasChunkAt(target)) {
                    return false;
                }
                UUID owner = frameIndex.get(target);
                if (owner != null
                        && !owner.equals(placed.assemblyId())
                        && !targetFrames.containsKey(owner)) {
                    return false;
                }
            }
            replacements.put(placed.assemblyId(), placed);
        }
        pendingContraptionMoves.putAll(replacements);
        if (!replacements.isEmpty()) {
            setDirty();
        }
        return true;
    }

    /** Commits every journaled Create placement as one reversible structural transaction. */
    public boolean finalizeContraptionPlacement(ServerLevel level, Collection<UUID> assemblyIds) {
        List<PendingContraptionMove> moves = assemblyIds.stream()
                .distinct()
                .map(pendingContraptionMoves::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (moves.size() != new java.util.HashSet<>(assemblyIds).size()
                || moves.stream().anyMatch(move -> !move.hasPlacement())) {
            return false;
        }

        Map<UUID, FrameOrientation> targetOrientations = new HashMap<>();
        Map<UUID, AssemblySnapshot> assemblySnapshots = new HashMap<>();
        Map<BlockPos, FrameSnapshot> frameSnapshots = new HashMap<>();
        Set<UUID> movingIds = moves.stream()
                .map(PendingContraptionMove::assemblyId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        for (PendingContraptionMove move : moves) {
            MechanismAssembly assembly = assemblies.get(move.assemblyId());
            FrameOrientation targetOrientation = FrameOrientation.fromQuaternion(
                    move.finalPose().orientation(new Quaterniond())).orElse(null);
            if (assembly == null
                    || targetOrientation == null
                    || !targetOrientation.isUpright()
                    || !assembly.frames().equals(move.sourceFrames())) {
                return false;
            }
            targetOrientations.put(assembly.id(), targetOrientation);
            assemblySnapshots.put(assembly.id(), new AssemblySnapshot(
                    assembly.origin(), Set.copyOf(assembly.frames()), assembly.orientation(), assembly.poseTarget()));

            for (BlockPos source : move.sourceFrames()) {
                if (!assembly.id().equals(frameIndex.get(source))) {
                    return false;
                }
                BlockPos logical = assembly.logicalFrameOffset(source);
                BlockPos expected = move.targetOrigin().offset(targetOrientation.toPhysical(logical));
                if (!move.targetFrames().contains(expected)) {
                    return false;
                }
            }
            for (BlockPos target : move.targetFrames()) {
                if (!level.hasChunkAt(target)
                        || !level.getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())
                        || !(level.getBlockEntity(target) instanceof MechanismFrameBlockEntity frame)) {
                    return false;
                }
                UUID owner = frameIndex.get(target);
                if (owner != null && !owner.equals(assembly.id()) && !movingIds.contains(owner)) {
                    return false;
                }
                frameSnapshots.putIfAbsent(target, FrameSnapshot.capture(level, target, frame));
            }
        }

        try {
            moves.forEach(move -> move.sourceFrames().forEach(source -> {
                if (move.assemblyId().equals(frameIndex.get(source))) {
                    frameIndex.remove(source);
                }
            }));

            for (PendingContraptionMove move : moves) {
                MechanismAssembly assembly = assemblies.get(move.assemblyId());
                FrameOrientation targetOrientation = targetOrientations.get(move.assemblyId());
                assembly.relocate(move.targetOrigin(), move.targetFrames(), targetOrientation);
                assembly.setPoseTarget(move.finalPose());
                move.targetFrames().forEach(target -> frameIndex.put(target, assembly.id()));
            }

            // The journal remains live through all structural synchronization. Any neighbour update
            // caused by these writes therefore sees the macro-mini bridges as quiesced.
            int synchronizedFrames = 0;
            for (PendingContraptionMove move : moves) {
                MechanismAssembly assembly = assemblies.get(move.assemblyId());
                for (BlockPos target : move.targetFrames()) {
                    syncFrameFacing(level, target, assembly.orientation());
                    syncFrameBlockEntity(level, target, assembly);
                    contraptionCommitProbe.afterFrameSynchronized(assembly.id(), target, ++synchronizedFrames);
                }
                ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
                if (subLevel != null && !subLevel.isRemoved()) {
                    for (BlockPos target : move.targetFrames()) {
                        syncFrameState(level, assembly, subLevel, target);
                    }
                } else {
                    for (BlockPos target : move.targetFrames()) {
                        syncEmptyFrameState(level, target);
                    }
                }
            }

            // Commit point: mappings, EMPTY, orientation, occupancy and frameIndex are complete.
            moves.forEach(move -> pendingContraptionMoves.remove(move.assemblyId()));
            invalidContraptionMovesLogged.removeAll(movingIds);
            setDirty();
            CreateContraptionBoundaryLifecycle.reconnect(level, movingIds);
            return true;
        } catch (RuntimeException exception) {
            // Re-arm the journal first so every rollback write remains fail-closed at the boundary.
            moves.forEach(move -> pendingContraptionMoves.put(move.assemblyId(), move));
            moves.forEach(move -> move.targetFrames().forEach(target -> {
                if (move.assemblyId().equals(frameIndex.get(target))) {
                    frameIndex.remove(target);
                }
            }));
            for (Map.Entry<UUID, AssemblySnapshot> entry : assemblySnapshots.entrySet()) {
                MechanismAssembly assembly = assemblies.get(entry.getKey());
                AssemblySnapshot snapshot = entry.getValue();
                assembly.relocate(snapshot.origin(), snapshot.frames(), snapshot.orientation());
                assembly.setPoseTarget(snapshot.pose());
                snapshot.frames().forEach(frame -> frameIndex.put(frame, assembly.id()));
            }
            for (Map.Entry<BlockPos, FrameSnapshot> entry : frameSnapshots.entrySet()) {
                entry.getValue().restore(level, entry.getKey());
            }
            setDirty();
            try {
                CreateContraptionBoundaryLifecycle.disconnect(level, movingIds);
            } catch (RuntimeException recoveryException) {
                exception.addSuppressed(recoveryException);
            }
            AntikytheraMechanism.LOGGER.error(
                    "Could not commit Create contraption placement; assembly, index, Frame BlockEntity mappings and block states were rolled back and journals retained",
                    exception);
            return false;
        }
    }

    /**
     * Separates the exact Frame subset that one Sable moveBlocks call is about to relocate when that
     * subset is only part of a larger logical assembly.
     *
     * <p>Sable host splitting creates a new physical SubLevel by moving one connected physical
     * component out of the old host. If that component contains only some Mechanism Frames, treating
     * the first moved Frame as a relocation of the complete Antikythera assembly creates an impossible
     * journal: the stationary Frames can never appear at the prepared destination. Partitioning before
     * Sable writes its first block makes every subsequent relocation journal complete by construction.
     * The original UUID/mini coordinate system stays with the side containing the semantic origin;
     * the other side receives the normal transactional SPLIT transfer and its own managed child.</p>
     */
    public boolean partitionPartialAssembliesForSableMove(
            ServerLevel level,
            Collection<BlockPos> movedBlockPositions) {
        Set<BlockPos> movedBlocks = movedBlockPositions.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (movedBlocks.isEmpty()) {
            return true;
        }

        Map<UUID, Set<BlockPos>> movedFramesByAssembly = new HashMap<>();
        for (BlockPos position : movedBlocks) {
            if (!level.getBlockState(position).is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            MechanismAssembly assembly = getAssemblyAt(position).orElse(null);
            if (assembly != null) {
                movedFramesByAssembly
                        .computeIfAbsent(assembly.id(), ignored -> new java.util.HashSet<>())
                        .add(position.immutable());
            }
        }

        for (Map.Entry<UUID, Set<BlockPos>> entry : movedFramesByAssembly.entrySet()) {
            MechanismAssembly source = assemblies.get(entry.getKey());
            Set<BlockPos> movingFrames = Set.copyOf(entry.getValue());
            if (source == null
                    || pendingPistonMoves.containsKey(source.id())
                    || pendingContraptionMoves.containsKey(source.id())
                    || contentRecoveryLocks.contains(source.id())) {
                return false;
            }
            if (!source.frames().containsAll(movingFrames)) {
                return false;
            }
            if (source.frames().equals(movingFrames)) {
                continue;
            }
            if (!source.frames().contains(source.origin())) {
                AntikytheraMechanism.LOGGER.error(
                        "Refused partial Sable partition for assembly {} because its semantic origin {} is not an owned Frame",
                        source.id(), source.origin());
                return false;
            }

            Set<BlockPos> stationaryFrames = source.frames().stream()
                    .filter(frame -> !movingFrames.contains(frame))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean originalSideMoves = movingFrames.contains(source.origin());
            Set<BlockPos> extractedFrames = originalSideMoves ? stationaryFrames : movingFrames;
            if (extractedFrames.isEmpty() || extractedFrames.size() >= source.frames().size()) {
                return false;
            }

            // Keep the new assembly's origin in the component that ordinary FrameGraph maintenance
            // will retain (largest, then deterministic position). This preserves its mapping even if
            // the extracted side itself contains several disconnected components and is split later.
            Set<BlockPos> primaryExtractedComponent = FrameGraph.connectedComponents(extractedFrames).getFirst();
            BlockPos extractedOrigin = primaryExtractedComponent.stream()
                    .min(framePositionOrder())
                    .orElseThrow();
            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), extractedOrigin, extractedFrames, source.orientation());
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
                    source.poseTarget(), source.logicalFrameOffset(extractedOrigin)));

            assemblies.put(split.id(), split);
            extractedFrames.forEach(frame -> frameIndex.put(frame, split.id()));
            AssemblyContentTransferService.TransferResult transferResult =
                    AssemblyContentTransferService.transferFrames(
                            level,
                            source,
                            split,
                            extractedFrames,
                            AssemblyLifecycleListener.TransferKind.SPLIT);
            if (transferResult == AssemblyContentTransferService.TransferResult.SUCCESS) {
                source.removeFrames(extractedFrames);
                extractedFrames.forEach(frame -> syncFrameBlockEntity(level, frame, split));
                if (!source.frames().contains(source.origin())) {
                    throw new IllegalStateException(
                            "Partial Sable partition detached the semantic origin of assembly " + source.id());
                }
                AntikytheraMechanism.LOGGER.debug(
                        "Partitioned assembly {} before partial Sable host move: moving={}, retainedOriginal={}, split={}",
                        source.id(), movingFrames, source.frames(), split.id());
                setDirty();
                continue;
            }

            if (transferResult == AssemblyContentTransferService.TransferResult.ROLLED_BACK) {
                MechanismSubLevelService.remove(level, split);
                assemblies.remove(split.id());
                extractedFrames.forEach(frame -> frameIndex.put(frame, source.id()));
                AntikytheraMechanism.LOGGER.error(
                        "Could not partition assembly {} before partial Sable host move; aborting the physical move",
                        source.id());
                setDirty();
                return false;
            }

            lockContentRecovery(source, split, "Sable partial-host split");
            return false;
        }
        return true;
    }

    /**
     * Atomically journals every complete frame assembly affected by one resolved
     * vanilla piston operation. Returning false means the event must be cancelled.
     */
    public boolean preparePistonMoves(
            ServerLevel level,
            BlockPos pistonPosition,
            Direction movementDirection,
            boolean extending,
            Collection<BlockPos> resolvedPushPositions) {
        Set<BlockPos> allPushed = resolvedPushPositions.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<UUID, Set<BlockPos>> movedFramesByAssembly = new HashMap<>();

        for (BlockPos source : allPushed) {
            if (!level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get())) {
                continue;
            }
            MechanismAssembly assembly = getAssemblyAt(source).orElse(null);
            if (assembly == null
                    || pendingPistonMoves.containsKey(assembly.id())
                    || pendingContraptionMoves.containsKey(assembly.id())
                    || contentRecoveryLocks.contains(assembly.id())) {
                return false;
            }
            BlockEntity blockEntity = level.getBlockEntity(source);
            if (!(blockEntity instanceof MechanismFrameBlockEntity)) {
                return false;
            }
            syncFrameBlockEntity(level, source, assembly);
            movedFramesByAssembly
                    .computeIfAbsent(assembly.id(), ignored -> new java.util.HashSet<>())
                    .add(source);
        }

        if (movedFramesByAssembly.isEmpty()) {
            return true;
        }

        BlockPos delta = new BlockPos(
                movementDirection.getStepX(),
                movementDirection.getStepY(),
                movementDirection.getStepZ());
        List<PendingPistonMove> prepared = new ArrayList<>();
        for (Map.Entry<UUID, Set<BlockPos>> entry : movedFramesByAssembly.entrySet()) {
            MechanismAssembly assembly = assemblies.get(entry.getKey());
            if (assembly == null || !assembly.frames().equals(entry.getValue())) {
                // Moving a subset would silently change topology while the logical assembly still
                // represents the complete connected Frame graph.
                return false;
            }
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null && assembly.subLevelId() != null) {
                return false;
            }
            if (subLevel != null
                    && (subLevel.isRemoved()
                            || Math.abs(subLevel.logicalPose().scale().x() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                            || Math.abs(subLevel.logicalPose().scale().y() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                            || Math.abs(subLevel.logicalPose().scale().z() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6)) {
                return false;
            }
            for (BlockPos frame : assembly.frames()) {
                if (!level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())) {
                    return false;
                }
                UUID indexed = frameIndex.get(frame);
                if (!assembly.id().equals(indexed)) {
                    return false;
                }
                BlockPos destination = frame.offset(delta);
                UUID destinationOwner = frameIndex.get(destination);
                if (destinationOwner != null && !destinationOwner.equals(assembly.id())) {
                    return false;
                }
            }
            prepared.add(new PendingPistonMove(
                    assembly.id(),
                    pistonPosition,
                    delta,
                    assembly.frames(),
                    assembly.poseTarget(),
                    level.getGameTime(),
                    extending));
        }

        prepared.forEach(move -> pendingPistonMoves.put(move.assemblyId(), move));
        prepared.forEach(move -> AntikytheraMechanism.LOGGER.debug(
                "Journaled piston move for assembly {}: {} -> {}, extending={}",
                move.assemblyId(),
                move.sourceFrames(),
                move.destinationFrames(),
                move.extending()));
        setDirty();
        return true;
    }

    /** True only for the source/destination positions covered by a live journal. */
    public boolean isPistonLifecycleTransition(BlockPos framePosition) {
        for (PendingPistonMove move : pendingPistonMoves.values()) {
            if (move.sourceFrames().contains(framePosition)
                    || move.destinationFrames().contains(framePosition)) {
                return true;
            }
        }
        return false;
    }

    public boolean isContraptionLifecycleTransition(BlockPos framePosition) {
        return pendingContraptionMoves.values().stream().anyMatch(move -> move.covers(framePosition));
    }

    /** True only for a journaled parent-world relocation, never a recovery lock. */
    public boolean isPhysicalRelocationTransition(BlockPos framePosition) {
        return isPistonLifecycleTransition(framePosition) || isContraptionLifecycleTransition(framePosition);
    }

    /** Covers active physical relocation and fail-closed content-transfer recovery. */
    public boolean isFrameLifecycleLocked(BlockPos framePosition) {
        if (isPhysicalRelocationTransition(framePosition)) {
            return true;
        }
        UUID assemblyId = frameIndex.get(framePosition);
        return assemblyId != null && contentRecoveryLocks.contains(assemblyId);
    }

    /** Called from the piston carrier mixin after vanilla advances or settles it. */
    public void onPistonCarrierTick(
            ServerLevel level,
            BlockPos carrierPosition,
            PistonMovingBlockEntity carrier) {
        if (!carrier.getMovedState().is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }
        PendingPistonMove move = pendingPistonMoves.values().stream()
                .filter(candidate -> candidate.matchesCarrierMetadata(
                        carrierPosition,
                        carrier.getMovementDirection(),
                        carrier.isExtending(),
                        carrier.isSourcePiston()))
                .findFirst()
                .orElse(null);
        if (move != null) {
            reconcilePendingPistonMove(level, move);
        }
    }

    public MechanismAssembly onFramePlaced(ServerLevel level, BlockPos framePos) {
        Optional<MechanismAssembly> existing = getAssemblyAt(framePos);
        if (existing.isPresent()) {
            if (!contentRecoveryLocks.contains(existing.get().id())) {
                syncFrameBlockEntity(level, framePos, existing.get());
            }
            return existing.get();
        }

        FrameOrientation placementOrientation = frameOrientation(level.getBlockState(framePos));
        Map<UUID, MechanismAssembly> neighbors = compatibleAdjacentAssemblies(framePos, placementOrientation);
        neighbors.values().removeIf(neighbor ->
                pendingPistonMoves.containsKey(neighbor.id())
                        || pendingContraptionMoves.containsKey(neighbor.id())
                        || contentRecoveryLocks.contains(neighbor.id()));

        MechanismAssembly selected = neighbors.values().stream()
                .filter(candidate -> neighbors.values().stream().allMatch(other ->
                        candidate == other || AssemblyOrientationMath.compatiblePhysical(candidate, other, 1.0E-6)))
                .min(assemblySurvivorOrder())
                .orElse(null);
        if (selected == null) {
            selected = new MechanismAssembly(
                    UUID.randomUUID(), framePos, Set.of(framePos), placementOrientation);
            assemblies.put(selected.id(), selected);
        } else {
            selected.addFrame(framePos);
        }

        frameIndex.put(framePos.immutable(), selected.id());
        syncFrameBlockEntity(level, framePos, selected);
        syncEmptyFrameState(level, framePos);

        for (MechanismAssembly neighbor : List.copyOf(neighbors.values())) {
            if (neighbor != selected && AssemblyOrientationMath.compatiblePhysical(selected, neighbor, 1.0E-6)) {
                mergeAssemblies(level, selected, neighbor);
            }
        }
        setDirty();
        return selected;
    }

    public void onFrameRemoved(ServerLevel level, BlockPos framePos) {
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        if (assembly == null) {
            return;
        }

        if (!evacuatedFrames.contains(framePos)
                && !evacuateFrame(level, framePos, FrameEvacuationService.Cause.generic())) {
            AntikytheraMechanism.LOGGER.error(
                    "Frame {} was removed before its mini contents could be evacuated; "
                            + "preserving its assembly index and physical content reference for recovery",
                    framePos);
            setDirty();
            return;
        }

        frameIndex.remove(framePos);
        evacuatedFrames.remove(framePos);
        assembly.removeFrame(framePos);
        if (assembly.frames().isEmpty()) {
            MechanismSubLevelService.remove(level, assembly);
            assemblies.remove(assembly.id());
        } else {
            splitDisconnectedAssembly(level, assembly);
        }
        setDirty();
    }

    public boolean evacuateFrame(
            ServerLevel level,
            BlockPos framePos,
            FrameEvacuationService.Cause cause) {
        if (evacuatedFrames.contains(framePos)) {
            return true;
        }
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        if (assembly == null) {
            return true;
        }
        PendingPistonMove pendingMove = pendingPistonMoves.get(assembly.id());
        if (pendingMove != null
                || pendingContraptionMoves.containsKey(assembly.id())
                || contentRecoveryLocks.contains(assembly.id())) {
            // A moving frame is represented by vanilla's piston carrier, not by a
            // destructible parent frame. An invalid/recovering journal is also a
            // hard lock: evacuating against ambiguous source/destination metadata
            // would be more destructive than refusing the break.
            return false;
        }
        BlockPos immutablePos = framePos.immutable();
        if (!evacuatingFrames.add(immutablePos)) {
            return false;
        }
        try {
            FrameEvacuationService.DetailedResult result =
                    FrameEvacuationService.evacuateDetailed(level, assembly, framePos, cause);
            if (result.result() == FrameEvacuationService.Result.SUCCESS) {
                evacuatedFrames.add(immutablePos);
                refreshFrame(level, framePos);
                return true;
            }
            if (result.result() == FrameEvacuationService.Result.RECOVERY_REQUIRED) {
                PendingFrameEvacuation journal = java.util.Objects.requireNonNull(result.recoveryJournal());
                pendingFrameEvacuations.put(assembly.id(), journal);
                contentRecoveryLocks.add(assembly.id());
                setDirty();
                AntikytheraMechanism.LOGGER.error(
                        "Locked assembly {} after evacuation of frame {} could not be rolled back exactly. "
                                + "Its physical content reference and persistent eight-cell recovery journal were retained.",
                        assembly.id(),
                        framePos);
            }
            return false;
        } finally {
            evacuatingFrames.remove(immutablePos);
        }
    }

    public boolean isFrameEvacuated(BlockPos framePos) {
        return evacuatedFrames.contains(framePos);
    }

    public void tick(ServerLevel level) {
        for (PendingPistonMove move : new ArrayList<>(pendingPistonMoves.values())) {
            reconcilePendingPistonMove(level, move);
        }
        reconcilePendingContraptionMoves(level);

        long gameTime = level.getGameTime();
        if (lastMaintenanceTick != Long.MIN_VALUE && gameTime - lastMaintenanceTick < 20) {
            return;
        }
        lastMaintenanceTick = gameTime;

        reconcileLoadedFrameRecords(level);
        reconcileConnectedAssemblies(level);
        for (MechanismAssembly assembly : new ArrayList<>(assemblies.values())) {
            if (!pendingPistonMoves.containsKey(assembly.id())
                    && !pendingContraptionMoves.containsKey(assembly.id())
                    && !contentRecoveryLocks.contains(assembly.id())) {
                splitDisconnectedAssembly(level, assembly);
            }
        }

        for (MechanismAssembly assembly : new ArrayList<>(assemblies.values())) {
            if (pendingPistonMoves.containsKey(assembly.id())
                    || pendingContraptionMoves.containsKey(assembly.id())
                    || contentRecoveryLocks.contains(assembly.id())) {
                continue;
            }

            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            for (BlockPos framePos : assembly.frames()) {
                if (!level.hasChunkAt(framePos)) {
                    continue;
                }
                syncFrameBlockEntity(level, framePos, assembly);
                if (subLevel == null) {
                    syncEmptyFrameState(level, framePos);
                } else {
                    syncFrameState(level, assembly, subLevel, framePos);
                }
            }
        }
    }

    public void refreshFrame(ServerLevel level, BlockPos framePos) {
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        if (assembly == null
                || pendingContraptionMoves.containsKey(assembly.id())
                || contentRecoveryLocks.contains(assembly.id())) {
            return;
        }
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            syncEmptyFrameState(level, framePos);
        } else {
            syncFrameState(level, assembly, subLevel, framePos);
        }
    }

    private boolean mergeAssemblies(
            ServerLevel level,
            MechanismAssembly target,
            MechanismAssembly source) {
        if (target == source || !assemblies.containsKey(source.id())) {
            return true;
        }
        if (pendingPistonMoves.containsKey(target.id())
                || pendingPistonMoves.containsKey(source.id())
                || pendingContraptionMoves.containsKey(target.id())
                || pendingContraptionMoves.containsKey(source.id())
                || contentRecoveryLocks.contains(target.id())
                || contentRecoveryLocks.contains(source.id())) {
            return false;
        }
        if (!AssemblyOrientationMath.compatiblePhysical(target, source, 1.0E-6)) {
            AntikytheraMechanism.LOGGER.debug(
                    "Refused to merge assemblies {} and {} because their physical/logical orientations or world poses are incompatible",
                    target.id(),
                    source.id());
            return false;
        }

        List<BlockPos> sourceFrames = List.copyOf(source.frames());
        target.addFrames(sourceFrames);
        sourceFrames.forEach(pos -> frameIndex.put(pos, target.id()));

        AssemblyContentTransferService.TransferResult transferResult =
                AssemblyContentTransferService.transferFrames(
                        level,
                        source,
                        target,
                        sourceFrames,
                        AssemblyLifecycleListener.TransferKind.MERGE);
        if (transferResult == AssemblyContentTransferService.TransferResult.RECOVERY_REQUIRED) {
            lockContentRecovery(source, target, "merge");
            return false;
        }
        if (transferResult == AssemblyContentTransferService.TransferResult.ROLLED_BACK) {
            target.removeFrames(sourceFrames);
            sourceFrames.forEach(pos -> frameIndex.put(pos, source.id()));
            AntikytheraMechanism.LOGGER.error(
                    "Could not merge mechanism assembly {} into {}; the operation will be retried",
                    source.id(),
                    target.id());
            return false;
        }

        MechanismSubLevelService.remove(level, source);
        assemblies.remove(source.id());
        sourceFrames.forEach(pos -> syncFrameBlockEntity(level, pos, target));
        setDirty();
        return true;
    }

    private void splitDisconnectedAssembly(ServerLevel level, MechanismAssembly source) {
        if (!assemblies.containsKey(source.id())
                || pendingPistonMoves.containsKey(source.id())
                || pendingContraptionMoves.containsKey(source.id())
                || contentRecoveryLocks.contains(source.id())) {
            return;
        }
        List<Set<BlockPos>> components = FrameGraph.connectedComponents(source.frames());
        if (components.size() <= 1) {
            return;
        }

        Set<BlockPos> retained = components.getFirst();
        for (int index = 1; index < components.size(); index++) {
            Set<BlockPos> component = components.get(index);
            BlockPos origin = component.stream().min(framePositionOrder()).orElseThrow();
            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), origin, component, source.orientation());
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
                    source.poseTarget(), source.logicalFrameOffset(origin)));
            assemblies.put(split.id(), split);
            component.forEach(pos -> frameIndex.put(pos, split.id()));

            AssemblyContentTransferService.TransferResult transferResult =
                    AssemblyContentTransferService.transferFrames(
                            level,
                            source,
                            split,
                            component,
                            AssemblyLifecycleListener.TransferKind.SPLIT);
            if (transferResult == AssemblyContentTransferService.TransferResult.SUCCESS) {
                source.removeFrames(component);
                component.forEach(pos -> syncFrameBlockEntity(level, pos, split));
            } else if (transferResult == AssemblyContentTransferService.TransferResult.ROLLED_BACK) {
                MechanismSubLevelService.remove(level, split);
                assemblies.remove(split.id());
                component.forEach(pos -> frameIndex.put(pos, source.id()));
                AntikytheraMechanism.LOGGER.error(
                        "Could not split component from mechanism assembly {}; the operation will be retried",
                        source.id());
            } else {
                lockContentRecovery(source, split, "split");
                return;
            }
        }

        if (!source.frames().containsAll(retained)) {
            throw new IllegalStateException("Retained frame component was lost while splitting " + source.id());
        }
        setDirty();
    }

    private void lockContentRecovery(
            MechanismAssembly source,
            MechanismAssembly target,
            String operation) {
        contentRecoveryLocks.add(source.id());
        contentRecoveryLocks.add(target.id());
        setDirty();
        AntikytheraMechanism.LOGGER.error(
                "CRITICAL: {} transfer between assemblies {} and {} requires manual recovery. "
                        + "Both assembly records and any physical Sable content worlds were retained and locked; no automatic remove, merge, split, movement or evacuation will run.",
                operation,
                source.id(),
                target.id());
    }

    private void reconcileConnectedAssemblies(ServerLevel level) {
        while (true) {
            MechanismAssembly left = null;
            MechanismAssembly right = null;
            outer:
            for (Map.Entry<BlockPos, UUID> entry : frameIndex.entrySet()) {
                if (pendingPistonMoves.containsKey(entry.getValue())
                        || pendingContraptionMoves.containsKey(entry.getValue())
                        || contentRecoveryLocks.contains(entry.getValue())) {
                    continue;
                }
                for (Direction direction : Direction.values()) {
                    UUID neighborId = frameIndex.get(entry.getKey().relative(direction));
                    if (neighborId == null
                            || neighborId.equals(entry.getValue())
                            || pendingPistonMoves.containsKey(neighborId)
                            || pendingContraptionMoves.containsKey(neighborId)
                            || contentRecoveryLocks.contains(neighborId)) {
                        continue;
                    }
                    MechanismAssembly candidateLeft = assemblies.get(entry.getValue());
                    MechanismAssembly candidateRight = assemblies.get(neighborId);
                    if (candidateLeft != null
                            && candidateRight != null
                            && AssemblyOrientationMath.compatiblePhysical(
                                    candidateLeft, candidateRight, 1.0E-6)) {
                        left = candidateLeft;
                        right = candidateRight;
                        break outer;
                    }
                }
            }
            if (left == null || right == null) {
                return;
            }
            MechanismAssembly target = assemblySurvivorOrder().compare(left, right) <= 0 ? left : right;
            MechanismAssembly source = target == left ? right : left;
            if (!mergeAssemblies(level, target, source)) {
                return;
            }
        }
    }

    private void reconcileLoadedFrameRecords(ServerLevel level) {
        for (MechanismAssembly assembly : assemblies.values()) {
            if (contentRecoveryLocks.contains(assembly.id())
                    || pendingPistonMoves.containsKey(assembly.id())
                    || pendingContraptionMoves.containsKey(assembly.id())) {
                continue;
            }
            for (BlockPos frame : assembly.frames()) {
                if (level.hasChunkAt(frame)
                        && !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())) {
                    contentRecoveryLocks.add(assembly.id());
                    setDirty();
                    AntikytheraMechanism.LOGGER.error(
                            "Locked assembly {} because SavedData expects a frame at {} but the loaded parent chunk does not contain one. Its physical content reference was retained.",
                            assembly.id(),
                            frame);
                    break;
                }
            }
        }
    }

    private void reconcilePendingContraptionMoves(ServerLevel level) {
        for (PendingContraptionMove move : new ArrayList<>(pendingContraptionMoves.values())) {
            MechanismAssembly assembly = assemblies.get(move.assemblyId());
            if (assembly == null || !assembly.frames().equals(move.sourceFrames())) {
                logInvalidContraptionMove(
                        move,
                        "assembly metadata no longer matches its journaled source frames");
                continue;
            }
            if (!move.hasPlacement()
                    && allLoaded(level, move.sourceFrames())
                    && move.sourceFrames().stream().allMatch(source ->
                            level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get()))) {
                // Journal creation preceded extraction and Create never removed
                // the blocks. Restoring the start pose and dropping the journal
                // is therefore lossless.
                assembly.setPoseTarget(move.startPose());
                pendingContraptionMoves.remove(move.assemblyId());
                invalidContraptionMovesLogged.remove(move.assemblyId());
                setDirty();
                CreateContraptionBoundaryLifecycle.reconnect(level, Set.of(move.assemblyId()));
            }
        }

        List<PendingContraptionMove> placements = pendingContraptionMoves.values().stream()
                .filter(PendingContraptionMove::hasPlacement)
                .toList();
        if (placements.isEmpty()) {
            return;
        }
        boolean allSettled = placements.stream().allMatch(move ->
                allLoaded(level, move.targetFrames())
                        && move.targetFrames().stream().allMatch(target ->
                                level.getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())
                                        && level.getBlockEntity(target) instanceof MechanismFrameBlockEntity));
        if (allSettled) {
            finalizeContraptionPlacement(
                    level,
                    placements.stream().map(PendingContraptionMove::assemblyId).toList());
        }
    }

    private void logInvalidContraptionMove(PendingContraptionMove move, String reason) {
        if (invalidContraptionMovesLogged.add(move.assemblyId())) {
            AntikytheraMechanism.LOGGER.error(
                    "Blocked Create contraption recovery for assembly {}: {}. "
                            + "Its journal, assembly record and any physical content world were retained.",
                    move.assemblyId(),
                    reason);
        }
    }

    private void reconcilePendingPistonMove(ServerLevel level, PendingPistonMove move) {
        MechanismAssembly assembly = assemblies.get(move.assemblyId());
        if (assembly == null || !assembly.frames().equals(move.sourceFrames())) {
            logInvalidPistonMove(move, "assembly metadata no longer matches its journaled source frames");
            return;
        }
        if (!allLoaded(level, move.sourceFrames()) || !allLoaded(level, move.destinationFrames())) {
            // Do not infer a completed move from only one side of a chunk border.
            return;
        }

        if (move.sourceFrames().stream()
                        .allMatch(source -> level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get()))
                && !hasActivePistonCarrier(level, move)) {
            if (level.getGameTime() <= move.startedTick()) {
                // Piston retraction calls finalTick() on the old head carrier
                // after Pre and before creating the pulled-block carrier. Even a
                // future unexpected callback in that synchronous gap must not be
                // allowed to discard the freshly persisted journal.
                return;
            }
            // The Pre event ran, but vanilla never started (for example another
            // listener cancelled later). Nothing physical changed, so dropping the
            // journal and restoring the start pose is lossless.
            assembly.setPoseTarget(move.startPose());
            AntikytheraMechanism.LOGGER.debug(
                    "Discarding unstarted piston journal for assembly {} at game tick {} (started {})",
                    move.assemblyId(),
                    level.getGameTime(),
                    move.startedTick());
            pendingPistonMoves.remove(move.assemblyId());
            invalidPistonMovesLogged.remove(move.assemblyId());
            setDirty();
            return;
        }

        boolean duplicatedSourceFrame = move.sourceFrames().stream()
                .filter(source -> !move.destinationFrames().contains(source))
                .anyMatch(source -> level.getBlockState(source).is(ModRegistries.MECHANISM_FRAME.get()));
        if (duplicatedSourceFrame) {
            logInvalidPistonMove(move, "a source-only frame still exists after the carrier started");
            return;
        }

        MotionInspection inspection = inspectPistonMotion(level, move);
        switch (inspection.state()) {
            case UNAVAILABLE -> {
                // Never force-load arbitrary parent chunks from SavedData recovery.
                // The journal remains authoritative until the piston area is loaded.
            }
            case MOVING -> updatePoseTarget(move.assemblyId(), move.poseAtProgress(inspection.progress()));
            case SETTLED -> finalizePistonMove(level, assembly, move);
            case INVALID -> logInvalidPistonMove(
                    move,
                    "expected every destination to contain either its matching piston carrier or a settled frame");
        }
    }

    private MotionInspection inspectPistonMotion(ServerLevel level, PendingPistonMove move) {
        if (!allLoaded(level, move.destinationFrames())) {
            return new MotionInspection(MotionState.UNAVAILABLE, 0.0);
        }

        int settled = 0;
        double minimumProgress = 1.0;
        for (BlockPos destination : move.destinationFrames()) {
            if (level.getBlockState(destination).is(ModRegistries.MECHANISM_FRAME.get())) {
                if (!(level.getBlockEntity(destination) instanceof MechanismFrameBlockEntity)) {
                    return new MotionInspection(MotionState.INVALID, 0.0);
                }
                settled++;
                continue;
            }
            if (!isMatchingPistonCarrier(level, destination, move)) {
                return new MotionInspection(MotionState.INVALID, 0.0);
            }
            PistonMovingBlockEntity carrier = (PistonMovingBlockEntity) level.getBlockEntity(destination);
            minimumProgress = Math.min(minimumProgress, carrier.getProgress(1.0F));
        }

        if (settled == move.destinationFrames().size()) {
            return new MotionInspection(MotionState.SETTLED, 1.0);
        }
        return new MotionInspection(MotionState.MOVING, minimumProgress);
    }

    private boolean finalizePistonMove(
            ServerLevel level,
            MechanismAssembly assembly,
            PendingPistonMove move) {
        for (BlockPos source : move.sourceFrames()) {
            if (!assembly.id().equals(frameIndex.get(source))) {
                logInvalidPistonMove(move, "the frame index changed before piston settlement");
                return false;
            }
        }
        for (BlockPos destination : move.destinationFrames()) {
            if (!level.getBlockState(destination).is(ModRegistries.MECHANISM_FRAME.get())
                    || !(level.getBlockEntity(destination) instanceof MechanismFrameBlockEntity)) {
                return false;
            }
            UUID existingOwner = frameIndex.get(destination);
            if (existingOwner != null && !existingOwner.equals(assembly.id())) {
                logInvalidPistonMove(move, "a destination became owned by another assembly");
                return false;
            }
        }

        AssemblyPose previousPose = assembly.poseTarget();
        try {
            move.sourceFrames().forEach(source -> {
                if (assembly.id().equals(frameIndex.get(source))) {
                    frameIndex.remove(source);
                }
            });
            assembly.translate(move.delta());
            if (!assembly.frames().equals(move.destinationFrames())) {
                throw new IllegalStateException("Translated frame set does not match piston destinations");
            }
            assembly.setPoseTarget(move.poseAtProgress(1.0));
            move.destinationFrames().forEach(destination -> frameIndex.put(destination, assembly.id()));

            for (BlockPos destination : move.destinationFrames()) {
                syncFrameBlockEntity(level, destination, assembly);
            }
            ServerSubLevel subLevel = MechanismSubLevelService.get(level, assembly);
            if (subLevel != null && !subLevel.isRemoved()) {
                for (BlockPos destination : move.destinationFrames()) {
                    syncFrameState(level, assembly, subLevel, destination);
                }
            } else {
                for (BlockPos destination : move.destinationFrames()) {
                    syncEmptyFrameState(level, destination);
                }
            }

            pendingPistonMoves.remove(move.assemblyId());
            invalidPistonMovesLogged.remove(move.assemblyId());
            setDirty();
            return true;
        } catch (RuntimeException exception) {
            move.destinationFrames().forEach(destination -> {
                if (assembly.id().equals(frameIndex.get(destination))) {
                    frameIndex.remove(destination);
                }
            });
            if (assembly.frames().equals(move.destinationFrames())) {
                assembly.translate(new BlockPos(
                        -move.delta().getX(),
                        -move.delta().getY(),
                        -move.delta().getZ()));
            }
            assembly.setPoseTarget(previousPose);
            move.sourceFrames().forEach(source -> frameIndex.put(source, assembly.id()));
            setDirty();
            AntikytheraMechanism.LOGGER.error(
                    "Could not commit piston move for mechanism assembly {}; keeping its recovery journal",
                    assembly.id(),
                    exception);
            return false;
        }
    }

    private static boolean allLoaded(ServerLevel level, Collection<BlockPos> positions) {
        return positions.stream().allMatch(level::hasChunkAt);
    }

    private static boolean hasActivePistonCarrier(ServerLevel level, PendingPistonMove move) {
        return move.destinationFrames().stream()
                .anyMatch(destination -> level.hasChunkAt(destination)
                        && isMatchingPistonCarrier(level, destination, move));
    }

    private static boolean isMatchingPistonCarrier(
            ServerLevel level,
            BlockPos destination,
            PendingPistonMove move) {
        if (!level.getBlockState(destination).is(Blocks.MOVING_PISTON)) {
            return false;
        }
        return level.getBlockEntity(destination) instanceof PistonMovingBlockEntity carrier
                && carrier.getMovedState().is(ModRegistries.MECHANISM_FRAME.get())
                && move.matchesCarrierMetadata(
                        destination,
                        carrier.getMovementDirection(),
                        carrier.isExtending(),
                        carrier.isSourcePiston());
    }

    private void logInvalidPistonMove(PendingPistonMove move, String reason) {
        if (invalidPistonMovesLogged.add(move.assemblyId())) {
            AntikytheraMechanism.LOGGER.error(
                    "Blocked piston recovery for mechanism assembly {}: {}. "
                            + "No mini blocks or assembly metadata were deleted; the persisted journal remains for recovery.",
                    move.assemblyId(),
                    reason);
        }
    }

    private record AssemblySnapshot(
            BlockPos origin, Set<BlockPos> frames, FrameOrientation orientation, AssemblyPose pose) {}

    private record FrameSnapshot(
            BlockState state,
            UUID assemblyId,
            FrameOrientation orientation,
            BlockPos logicalOffset,
            int occupiedMask) {
        private static FrameSnapshot capture(
                ServerLevel level, BlockPos pos, MechanismFrameBlockEntity frame) {
            return new FrameSnapshot(
                    level.getBlockState(pos),
                    frame.getAssemblyId(),
                    frame.getFrameOrientation(),
                    frame.getLogicalFrameOffset(),
                    frame.getOccupiedMask());
        }

        private void restore(ServerLevel level, BlockPos pos) {
            BlockState current = level.getBlockState(pos);
            if (!current.equals(state)) {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }
            if (level.getBlockEntity(pos) instanceof MechanismFrameBlockEntity frame) {
                frame.setAssemblyMapping(assemblyId, orientation, logicalOffset);
                frame.setOccupiedMask(occupiedMask);
            }
        }
    }

    private enum MotionState {
        UNAVAILABLE,
        MOVING,
        SETTLED,
        INVALID
    }

    private record MotionInspection(MotionState state, double progress) {
    }

    private Map<UUID, MechanismAssembly> compatibleAdjacentAssemblies(
            BlockPos framePos, FrameOrientation orientation) {
        Map<UUID, MechanismAssembly> neighbors = new HashMap<>();
        for (Direction direction : Direction.values()) {
            MechanismAssembly neighbor = getAssemblyAt(framePos.relative(direction)).orElse(null);
            if (neighbor != null && neighbor.orientation().equals(orientation)) {
                neighbors.put(neighbor.id(), neighbor);
            }
        }
        return neighbors;
    }

    private static FrameOrientation placementOrientation(ServerLevel level, BlockPos framePos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(framePos.relative(direction));
            if (neighbor.is(ModRegistries.MECHANISM_FRAME.get())
                    && neighbor.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return new FrameOrientation(
                        Direction.UP, neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING));
            }
        }
        return FrameOrientation.IDENTITY;
    }

    private static FrameOrientation frameOrientation(BlockState state) {
        if (state.is(ModRegistries.MECHANISM_FRAME.get())
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return new FrameOrientation(Direction.UP, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return FrameOrientation.IDENTITY;
    }

    private static Comparator<MechanismAssembly> assemblySurvivorOrder() {
        return Comparator.<MechanismAssembly>comparingInt(assembly -> assembly.frames().size())
                .reversed()
                .thenComparing(MechanismAssembly::id);
    }

    private static Comparator<BlockPos> framePositionOrder() {
        return Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX());
    }

    private static void syncFrameBlockEntity(
            ServerLevel level, BlockPos pos, MechanismAssembly assembly) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MechanismFrameBlockEntity frame) {
            frame.setAssemblyMapping(
                    assembly.id(), assembly.orientation(), assembly.logicalFrameOffset(pos));
        }
    }

    private static void syncFrameFacing(
            ServerLevel level, BlockPos pos, FrameOrientation orientation) {
        BlockState state = level.getBlockState(pos);
        if (state.is(ModRegistries.MECHANISM_FRAME.get())
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && state.getValue(BlockStateProperties.HORIZONTAL_FACING) != orientation.front()) {
            level.setBlock(
                    pos,
                    state.setValue(BlockStateProperties.HORIZONTAL_FACING, orientation.front()),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    private static void syncEmptyFrameState(ServerLevel level, BlockPos framePos) {
        BlockEntity blockEntity = level.getBlockEntity(framePos);
        if (blockEntity instanceof MechanismFrameBlockEntity frame) {
            frame.setOccupiedMask(0);
        }
        if (level.getBlockState(framePos).getBlock() instanceof MechanismFrameBlock
                && !level.getBlockState(framePos).getValue(MechanismFrameBlock.EMPTY)) {
            level.setBlock(framePos, level.getBlockState(framePos).setValue(MechanismFrameBlock.EMPTY, true), 3);
        }
    }

    private static void syncFrameState(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel subLevel,
            BlockPos framePos) {
        int occupiedMask = 0;
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, framePos, x, y, z);
                    if (!subLevel.getPlot().getEmbeddedLevelAccessor().getBlockState(mini).isAir()) {
                        occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(x, y, z);
                    }
                }
            }
        }

        BlockEntity blockEntity = level.getBlockEntity(framePos);
        if (blockEntity instanceof MechanismFrameBlockEntity frame) {
            frame.setOccupiedMask(occupiedMask);
        }

        boolean empty = occupiedMask == 0;
        if (level.getBlockState(framePos).getBlock() instanceof MechanismFrameBlock
                && level.getBlockState(framePos).getValue(MechanismFrameBlock.EMPTY) != empty) {
            level.setBlock(framePos, level.getBlockState(framePos).setValue(MechanismFrameBlock.EMPTY, empty), 3);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        assemblies.values().stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .map(MechanismAssembly::save)
                .forEach(list::add);
        tag.put(ASSEMBLIES_TAG, list);

        ListTag pendingList = new ListTag();
        pendingPistonMoves.values().stream()
                .sorted((left, right) -> left.assemblyId().compareTo(right.assemblyId()))
                .map(PendingPistonMove::save)
                .forEach(pendingList::add);
        tag.put(PENDING_PISTON_MOVES_TAG, pendingList);

        ListTag pendingContraptions = new ListTag();
        pendingContraptionMoves.values().stream()
                .sorted((left, right) -> left.assemblyId().compareTo(right.assemblyId()))
                .map(PendingContraptionMove::save)
                .forEach(pendingContraptions::add);
        undecodedContraptionJournals.stream()
                .map(CompoundTag::copy)
                .forEach(pendingContraptions::add);
        tag.put(PENDING_CONTRAPTION_MOVES_TAG, pendingContraptions);

        ListTag pendingEvacuations = new ListTag();
        pendingFrameEvacuations.values().stream()
                .sorted((left, right) -> left.assemblyId().compareTo(right.assemblyId()))
                .map(PendingFrameEvacuation::save)
                .forEach(pendingEvacuations::add);
        undecodedFrameEvacuationJournals.stream()
                .map(CompoundTag::copy)
                .forEach(pendingEvacuations::add);
        tag.put(PENDING_FRAME_EVACUATIONS_TAG, pendingEvacuations);

        ListTag recoveryLocks = new ListTag();
        contentRecoveryLocks.stream().sorted().forEach(assemblyId -> {
            CompoundTag lock = new CompoundTag();
            lock.putUUID(ASSEMBLY_ID_TAG, assemblyId);
            recoveryLocks.add(lock);
        });
        tag.put(CONTENT_RECOVERY_LOCKS_TAG, recoveryLocks);
        return tag;
    }

    private static MechanismAssemblyManager load(CompoundTag tag, HolderLookup.Provider registries) {
        MechanismAssemblyManager manager = new MechanismAssemblyManager();
        boolean repairedCorruption = false;
        ListTag list = tag.getList(ASSEMBLIES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            MechanismAssembly assembly;
            try {
                assembly = MechanismAssembly.load(list.getCompound(index));
            } catch (RuntimeException exception) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not decode an assembly SavedData entry; leaving it untouched in the world for manual recovery",
                        exception);
                repairedCorruption = true;
                continue;
            }
            if (manager.assemblies.putIfAbsent(assembly.id(), assembly) != null) {
                manager.contentRecoveryLocks.add(assembly.id());
                repairedCorruption = true;
                AntikytheraMechanism.LOGGER.error(
                        "Duplicate assembly UUID {} in SavedData; retained the first record and locked it",
                        assembly.id());
                continue;
            }
            if (assembly.frames().isEmpty()) {
                manager.contentRecoveryLocks.add(assembly.id());
                repairedCorruption = true;
                AntikytheraMechanism.LOGGER.error(
                        "Assembly {} has no parent frames; retained and locked its physical content reference",
                        assembly.id());
            }
            for (BlockPos frame : assembly.frames()) {
                UUID previous = manager.frameIndex.putIfAbsent(frame, assembly.id());
                if (previous != null && !previous.equals(assembly.id())) {
                    manager.contentRecoveryLocks.add(previous);
                    manager.contentRecoveryLocks.add(assembly.id());
                    repairedCorruption = true;
                    AntikytheraMechanism.LOGGER.error(
                            "Frame {} is claimed by assemblies {} and {}; both were locked and neither physical content world was removed",
                            frame,
                            previous,
                            assembly.id());
                }
            }
        }
        ListTag pendingList = tag.getList(PENDING_PISTON_MOVES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < pendingList.size(); index++) {
            try {
                PendingPistonMove move = PendingPistonMove.load(pendingList.getCompound(index));
                // Keep even an orphaned/mismatched journal. Runtime reconciliation
                // will stop conservatively and report it; deleting the only crash
                // record during load would make manual recovery less reliable.
                manager.pendingPistonMoves.put(move.assemblyId(), move);
            } catch (IllegalArgumentException exception) {
                AntikytheraMechanism.LOGGER.error("Discarded an invalid persisted piston journal", exception);
            }
        }
        ListTag pendingContraptions = tag.getList(PENDING_CONTRAPTION_MOVES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < pendingContraptions.size(); index++) {
            CompoundTag persisted = pendingContraptions.getCompound(index).copy();
            try {
                PendingContraptionMove move = PendingContraptionMove.load(persisted, registries);
                manager.pendingContraptionMoves.put(move.assemblyId(), move);
            } catch (RuntimeException exception) {
                manager.undecodedContraptionJournals.add(persisted);
                if (persisted.hasUUID(ASSEMBLY_ID_TAG)) {
                    manager.contentRecoveryLocks.add(persisted.getUUID(ASSEMBLY_ID_TAG));
                }
                AntikytheraMechanism.LOGGER.error(
                        "Could not decode a persisted Create contraption journal; its raw NBT was retained and its assembly locked",
                        exception);
            }
        }
        ListTag pendingEvacuations = tag.getList(PENDING_FRAME_EVACUATIONS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < pendingEvacuations.size(); index++) {
            CompoundTag persisted = pendingEvacuations.getCompound(index).copy();
            try {
                PendingFrameEvacuation journal = PendingFrameEvacuation.load(persisted, registries);
                PendingFrameEvacuation previous =
                        manager.pendingFrameEvacuations.putIfAbsent(journal.assemblyId(), journal);
                manager.contentRecoveryLocks.add(journal.assemblyId());
                if (previous != null) {
                    manager.undecodedFrameEvacuationJournals.add(persisted);
                    AntikytheraMechanism.LOGGER.error(
                            "Assembly {} has more than one persisted frame evacuation journal; "
                                    + "all records were retained and the assembly remains locked",
                            journal.assemblyId());
                }
            } catch (RuntimeException exception) {
                // Missing mod blocks or corrupt registry payloads must not make the only recovery
                // snapshot disappear on the next save. Preserve the opaque NBT verbatim.
                manager.undecodedFrameEvacuationJournals.add(persisted);
                if (persisted.hasUUID(ASSEMBLY_ID_TAG)) {
                    manager.contentRecoveryLocks.add(persisted.getUUID(ASSEMBLY_ID_TAG));
                }
                AntikytheraMechanism.LOGGER.error(
                        "Could not decode a persisted frame evacuation journal; its raw NBT was retained",
                        exception);
            }
        }
        ListTag recoveryLocks = tag.getList(CONTENT_RECOVERY_LOCKS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < recoveryLocks.size(); index++) {
            CompoundTag lock = recoveryLocks.getCompound(index);
            if (lock.hasUUID(ASSEMBLY_ID_TAG)) {
                manager.contentRecoveryLocks.add(lock.getUUID(ASSEMBLY_ID_TAG));
            }
        }
        if (repairedCorruption) {
            manager.setDirty();
        }
        return manager;
    }
}
