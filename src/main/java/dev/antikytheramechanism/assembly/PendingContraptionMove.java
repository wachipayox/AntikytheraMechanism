package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persisted crash-recovery record for one assembly captured by Create. */
public final class PendingContraptionMove {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String SOURCE_FRAMES_TAG = "source_frames";
    private static final String RELEASED_SOURCE_FRAMES_TAG = "released_source_frames";
    private static final String SOURCE_ORIGIN_TAG = "source_origin";
    private static final String LOCAL_FRAMES_TAG = "local_frames";
    private static final String START_POSE_TAG = "start_pose";
    private static final String STARTED_TICK_TAG = "started_tick";
    private static final String TARGET_FRAMES_TAG = "target_frames";
    private static final String TARGET_ORIGIN_TAG = "target_origin";
    private static final String FINAL_POSE_TAG = "final_pose";
    private static final String CARRIED_BOUNDARY_BLOCKS_TAG = "carried_boundary_blocks";
    private static final String CONTROLLER_POSITION_TAG = "controller_position";
    private static final String BOUNDARY_POSITION_TAG = "position";
    private static final String BOUNDARY_STATE_TAG = "state";

    private final UUID assemblyId;
    private final Set<BlockPos> sourceFrames;
    private final Set<BlockPos> releasedSourceFrames;
    private final BlockPos sourceOrigin;
    private final Set<BlockPos> localFrames;
    private final AssemblyPose startPose;
    private final long startedTick;
    private final Set<BlockPos> targetFrames;
    private final BlockPos targetOrigin;
    private final AssemblyPose finalPose;
    private final Map<BlockPos, BlockState> carriedBoundaryBlocks;
    private final BlockPos controllerPosition;

    public PendingContraptionMove(
            UUID assemblyId,
            Collection<BlockPos> sourceFrames,
            BlockPos sourceOrigin,
            Collection<BlockPos> localFrames,
            AssemblyPose startPose,
            long startedTick) {
        this(assemblyId, sourceFrames, Set.of(), sourceOrigin, localFrames, startPose, startedTick,
                Map.of(), null, Set.of(), null, null);
    }

    public PendingContraptionMove(
            UUID assemblyId,
            Collection<BlockPos> sourceFrames,
            BlockPos sourceOrigin,
            Collection<BlockPos> localFrames,
            AssemblyPose startPose,
            long startedTick,
            Map<BlockPos, BlockState> carriedBoundaryBlocks) {
        this(assemblyId, sourceFrames, Set.of(), sourceOrigin, localFrames, startPose, startedTick,
                carriedBoundaryBlocks, null, Set.of(), null, null);
    }

    private PendingContraptionMove(
            UUID assemblyId,
            Collection<BlockPos> sourceFrames,
            Collection<BlockPos> releasedSourceFrames,
            BlockPos sourceOrigin,
            Collection<BlockPos> localFrames,
            AssemblyPose startPose,
            long startedTick,
            Map<BlockPos, BlockState> carriedBoundaryBlocks,
            BlockPos controllerPosition,
            Collection<BlockPos> targetFrames,
            BlockPos targetOrigin,
            AssemblyPose finalPose) {
        this.assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
        this.sourceFrames = immutableUnique(sourceFrames);
        this.releasedSourceFrames = immutableUnique(releasedSourceFrames);
        this.sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin").immutable();
        this.localFrames = immutableUnique(localFrames);
        this.startPose = Objects.requireNonNull(startPose, "startPose");
        this.startedTick = startedTick;
        this.targetFrames = immutableUnique(targetFrames);
        this.targetOrigin = targetOrigin == null ? null : targetOrigin.immutable();
        this.finalPose = finalPose;
        this.carriedBoundaryBlocks = immutableBoundaryBlocks(carriedBoundaryBlocks);
        this.controllerPosition = controllerPosition == null ? null : controllerPosition.immutable();

        if (this.sourceFrames.isEmpty()
                || this.sourceFrames.size() != sourceFrames.size()
                || this.releasedSourceFrames.size() != releasedSourceFrames.size()
                || !this.sourceFrames.containsAll(this.releasedSourceFrames)
                || this.localFrames.size() != localFrames.size()
                || this.sourceFrames.size() != this.localFrames.size()
                || findTranslation(this.localFrames, this.sourceFrames).isEmpty()) {
            throw new IllegalArgumentException("Invalid complete Create capture for assembly " + assemblyId);
        }
        boolean hasPlacement = !this.targetFrames.isEmpty() || this.targetOrigin != null || this.finalPose != null;
        if (hasPlacement) {
            if (this.targetOrigin == null
                    || this.finalPose == null
                    || this.targetFrames.size() != targetFrames.size()
                    || this.targetFrames.size() != this.sourceFrames.size()) {
                throw new IllegalArgumentException("Incomplete Create placement target for assembly " + assemblyId);
            }
            FrameOrientation sourceOrientation = FrameOrientation.fromQuaternion(
                    this.startPose.orientation(new Quaterniond())).orElse(null);
            FrameOrientation targetOrientation = FrameOrientation.fromQuaternion(
                    this.finalPose.orientation(new Quaterniond())).orElse(null);
            boolean rigidRelocation = sourceOrientation != null && targetOrientation != null
                    && this.sourceFrames.stream().allMatch(source -> {
                        BlockPos logical = sourceOrientation.toLogical(source.subtract(this.sourceOrigin));
                        return this.targetFrames.contains(
                                this.targetOrigin.offset(targetOrientation.toPhysical(logical)));
                    });
            if (!rigidRelocation) {
                throw new IllegalArgumentException(
                        "Create placement is not one orthogonal rigid mapping for assembly " + assemblyId);
            }
        }
    }

    /** Returns the same movement journal with the stationary Create controller recorded as staff pivot metadata. */
    public PendingContraptionMove withControllerPosition(BlockPos controllerPosition) {
        BlockPos immutable = Objects.requireNonNull(controllerPosition, "controllerPosition").immutable();
        if (immutable.equals(this.controllerPosition)) {
            return this;
        }
        return new PendingContraptionMove(
                assemblyId,
                sourceFrames,
                releasedSourceFrames,
                sourceOrigin,
                localFrames,
                startPose,
                startedTick,
                carriedBoundaryBlocks,
                immutable,
                targetFrames,
                targetOrigin,
                finalPose);
    }

    public PendingContraptionMove withPlacement(
            Collection<BlockPos> targetFrames,
            BlockPos targetOrigin,
            AssemblyPose finalPose) {
        return new PendingContraptionMove(
                assemblyId,
                sourceFrames,
                releasedSourceFrames,
                sourceOrigin,
                localFrames,
                startPose,
                startedTick,
                carriedBoundaryBlocks,
                controllerPosition,
                targetFrames,
                targetOrigin,
                finalPose);
    }

    /** Returns a new journal retaining the historical source while releasing its active ownership claim. */
    public PendingContraptionMove withReleasedSource(BlockPos source) {
        BlockPos immutable = Objects.requireNonNull(source, "source").immutable();
        if (!sourceFrames.contains(immutable)) {
            throw new IllegalArgumentException(
                    "Cannot release non-source Frame " + source + " from Create move " + assemblyId);
        }
        if (releasedSourceFrames.contains(immutable)) {
            return this;
        }
        Set<BlockPos> released = new HashSet<>(releasedSourceFrames);
        released.add(immutable);
        return new PendingContraptionMove(
                assemblyId,
                sourceFrames,
                released,
                sourceOrigin,
                localFrames,
                startPose,
                startedTick,
                carriedBoundaryBlocks,
                controllerPosition,
                targetFrames,
                targetOrigin,
                finalPose);
    }

    public UUID assemblyId() {
        return assemblyId;
    }

    public Set<BlockPos> sourceFrames() {
        return sourceFrames;
    }

    /** Source positions physically removed by Create and no longer reserved in the live frame index. */
    public Set<BlockPos> releasedSourceFrames() {
        return releasedSourceFrames;
    }

    public boolean isSourceReleased(BlockPos source) {
        return releasedSourceFrames.contains(source);
    }

    public BlockPos sourceOrigin() {
        return sourceOrigin;
    }

    public Set<BlockPos> localFrames() {
        return localFrames;
    }

    public AssemblyPose startPose() {
        return startPose;
    }

    public long startedTick() {
        return startedTick;
    }

    public Optional<BlockPos> controllerPosition() {
        return Optional.ofNullable(controllerPosition);
    }

    public boolean hasPlacement() {
        return targetOrigin != null;
    }

    public Set<BlockPos> targetFrames() {
        return targetFrames;
    }

    public BlockPos targetOrigin() {
        return targetOrigin;
    }

    public AssemblyPose finalPose() {
        return finalPose;
    }

    /** Local Create blocks adjacent to captured Frames, frozen for structural mini-boundary reads. */
    public Map<BlockPos, BlockState> carriedBoundaryBlocks() {
        return carriedBoundaryBlocks;
    }

    /** Returns the carried structural block that occupied this source parent position, if any. */
    public Optional<BlockState> carriedBoundaryStateAtSource(BlockPos sourcePosition) {
        BlockPos translation = findTranslation(localFrames, sourceFrames).orElseThrow();
        return Optional.ofNullable(carriedBoundaryBlocks.get(sourcePosition.subtract(translation)));
    }

    public BlockPos delta() {
        if (!hasPlacement()) {
            throw new IllegalStateException("Create placement has not been journaled");
        }
        return targetOrigin.subtract(sourceOrigin);
    }

    /** Historical recovery footprint; callers needing active ownership must honor releasedSourceFrames. */
    public boolean covers(BlockPos position) {
        return sourceFrames.contains(position) || targetFrames.contains(position);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putLongArray(SOURCE_FRAMES_TAG, sourceFrames.stream().map(BlockPos::asLong).toList());
        if (!releasedSourceFrames.isEmpty()) {
            tag.putLongArray(
                    RELEASED_SOURCE_FRAMES_TAG,
                    releasedSourceFrames.stream().map(BlockPos::asLong).toList());
        }
        tag.putLong(SOURCE_ORIGIN_TAG, sourceOrigin.asLong());
        tag.putLongArray(LOCAL_FRAMES_TAG, localFrames.stream().map(BlockPos::asLong).toList());
        tag.put(START_POSE_TAG, startPose.save());
        tag.putLong(STARTED_TICK_TAG, startedTick);
        if (controllerPosition != null) {
            tag.putLong(CONTROLLER_POSITION_TAG, controllerPosition.asLong());
        }
        if (!carriedBoundaryBlocks.isEmpty()) {
            ListTag boundaryBlocks = new ListTag();
            carriedBoundaryBlocks.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(FRAME_ORDER))
                    .forEach(entry -> {
                        CompoundTag block = new CompoundTag();
                        block.putLong(BOUNDARY_POSITION_TAG, entry.getKey().asLong());
                        block.put(BOUNDARY_STATE_TAG, NbtUtils.writeBlockState(entry.getValue()));
                        boundaryBlocks.add(block);
                    });
            tag.put(CARRIED_BOUNDARY_BLOCKS_TAG, boundaryBlocks);
        }
        if (hasPlacement()) {
            tag.putLongArray(TARGET_FRAMES_TAG, targetFrames.stream().map(BlockPos::asLong).toList());
            tag.putLong(TARGET_ORIGIN_TAG, targetOrigin.asLong());
            tag.put(FINAL_POSE_TAG, finalPose.save());
        }
        return tag;
    }

    /** Registry-free compatibility loader for legacy/unit journals with no BlockState snapshots. */
    public static PendingContraptionMove load(CompoundTag tag) {
        if (tag.contains(CARRIED_BOUNDARY_BLOCKS_TAG, Tag.TAG_LIST)
                && !tag.getList(CARRIED_BOUNDARY_BLOCKS_TAG, Tag.TAG_COMPOUND).isEmpty()) {
            throw new IllegalArgumentException("Create journal with carried boundary states requires registries");
        }
        return loadDecoded(tag, Map.of());
    }

    public static PendingContraptionMove load(CompoundTag tag, HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        return load(tag, registries.lookupOrThrow(Registries.BLOCK));
    }

    static PendingContraptionMove load(CompoundTag tag, HolderGetter<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        Map<BlockPos, BlockState> carried = new HashMap<>();
        ListTag boundaryBlocks = tag.getList(CARRIED_BOUNDARY_BLOCKS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < boundaryBlocks.size(); index++) {
            CompoundTag block = boundaryBlocks.getCompound(index);
            if (!block.contains(BOUNDARY_POSITION_TAG, Tag.TAG_ANY_NUMERIC)
                    || !block.contains(BOUNDARY_STATE_TAG, Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Incomplete carried Create boundary block snapshot");
            }
            BlockPos position = BlockPos.of(block.getLong(BOUNDARY_POSITION_TAG));
            BlockState state = NbtUtils.readBlockState(blocks, block.getCompound(BOUNDARY_STATE_TAG));
            if (!NbtUtils.writeBlockState(state).equals(block.getCompound(BOUNDARY_STATE_TAG))
                    || carried.putIfAbsent(position, state) != null) {
                throw new IllegalArgumentException("Invalid carried Create boundary block snapshot at " + position);
            }
        }
        return loadDecoded(tag, carried);
    }

    private static PendingContraptionMove loadDecoded(CompoundTag tag, Map<BlockPos, BlockState> carriedBoundaryBlocks) {
        if (!tag.hasUUID(ASSEMBLY_ID_TAG)
                || !tag.contains(SOURCE_ORIGIN_TAG)
                || !tag.contains(START_POSE_TAG, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Incomplete pending Create contraption journal");
        }
        Set<BlockPos> sources = positions(tag.getLongArray(SOURCE_FRAMES_TAG));
        Set<BlockPos> releasedSources = tag.contains(RELEASED_SOURCE_FRAMES_TAG, Tag.TAG_LONG_ARRAY)
                ? positions(tag.getLongArray(RELEASED_SOURCE_FRAMES_TAG))
                : Set.of();
        if (!sources.containsAll(releasedSources)) {
            throw new IllegalArgumentException("Released Create source is not part of source_frames");
        }
        Set<BlockPos> locals = positions(tag.getLongArray(LOCAL_FRAMES_TAG));
        BlockPos sourceOrigin = BlockPos.of(tag.getLong(SOURCE_ORIGIN_TAG));
        BlockPos controllerPosition = tag.contains(CONTROLLER_POSITION_TAG, Tag.TAG_ANY_NUMERIC)
                ? BlockPos.of(tag.getLong(CONTROLLER_POSITION_TAG))
                : null;
        AssemblyPose startPose = AssemblyPose.load(
                tag.getCompound(START_POSE_TAG), AssemblyPose.identityAt(sourceOrigin));
        boolean hasTargetFrames = tag.contains(TARGET_FRAMES_TAG, Tag.TAG_LONG_ARRAY);
        boolean hasTargetOrigin = tag.contains(TARGET_ORIGIN_TAG, Tag.TAG_ANY_NUMERIC);
        boolean hasFinalPose = tag.contains(FINAL_POSE_TAG, Tag.TAG_COMPOUND);
        if (hasTargetFrames || hasTargetOrigin || hasFinalPose) {
            if (!hasTargetFrames || !hasTargetOrigin || !hasFinalPose) {
                throw new IllegalArgumentException("Incomplete Create placement target in persisted journal");
            }
            BlockPos targetOrigin = BlockPos.of(tag.getLong(TARGET_ORIGIN_TAG));
            return new PendingContraptionMove(
                    tag.getUUID(ASSEMBLY_ID_TAG),
                    sources,
                    releasedSources,
                    sourceOrigin,
                    locals,
                    startPose,
                    tag.getLong(STARTED_TICK_TAG),
                    carriedBoundaryBlocks,
                    controllerPosition,
                    positions(tag.getLongArray(TARGET_FRAMES_TAG)),
                    targetOrigin,
                    AssemblyPose.load(tag.getCompound(FINAL_POSE_TAG), AssemblyPose.identityAt(targetOrigin)));
        }
        return new PendingContraptionMove(
                tag.getUUID(ASSEMBLY_ID_TAG),
                sources,
                releasedSources,
                sourceOrigin,
                locals,
                startPose,
                tag.getLong(STARTED_TICK_TAG),
                carriedBoundaryBlocks,
                controllerPosition,
                Set.of(),
                null,
                null);
    }

    public static Optional<BlockPos> findTranslation(
            Collection<BlockPos> localFrames,
            Collection<BlockPos> worldFrames) {
        Set<BlockPos> local = immutableUnique(localFrames);
        Set<BlockPos> world = immutableUnique(worldFrames);
        if (local.isEmpty()
                || local.size() != localFrames.size()
                || world.size() != worldFrames.size()
                || local.size() != world.size()) {
            return Optional.empty();
        }
        BlockPos localMinimum = local.stream().min(FRAME_ORDER).orElseThrow();
        BlockPos worldMinimum = world.stream().min(FRAME_ORDER).orElseThrow();
        BlockPos translation = worldMinimum.subtract(localMinimum);
        return local.stream().map(frame -> frame.offset(translation)).allMatch(world::contains)
                ? Optional.of(translation.immutable())
                : Optional.empty();
    }

    private static Map<BlockPos, BlockState> immutableBoundaryBlocks(Map<BlockPos, BlockState> states) {
        Objects.requireNonNull(states, "carriedBoundaryBlocks");
        Map<BlockPos, BlockState> copied = new HashMap<>();
        states.forEach((position, state) -> copied.put(
                Objects.requireNonNull(position, "boundary position").immutable(),
                Objects.requireNonNull(state, "boundary state")));
        return Collections.unmodifiableMap(copied);
    }

    private static Set<BlockPos> immutableUnique(Collection<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        Set<BlockPos> result = new HashSet<>();
        positions.forEach(position -> result.add(Objects.requireNonNull(position, "position").immutable()));
        return Collections.unmodifiableSet(result);
    }

    private static Set<BlockPos> positions(long[] packedPositions) {
        Set<BlockPos> result = new HashSet<>();
        for (long packed : packedPositions) {
            if (!result.add(BlockPos.of(packed))) {
                throw new IllegalArgumentException("Duplicate position in pending Create contraption journal");
            }
        }
        return result;
    }

    private static final java.util.Comparator<BlockPos> FRAME_ORDER =
            java.util.Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(position -> position.getY())
                    .thenComparingInt(position -> position.getZ());
}
