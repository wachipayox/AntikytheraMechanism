package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaterniond;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persisted crash-recovery record for one assembly captured by Create. */
public final class PendingContraptionMove {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String SOURCE_FRAMES_TAG = "source_frames";
    private static final String SOURCE_ORIGIN_TAG = "source_origin";
    private static final String LOCAL_FRAMES_TAG = "local_frames";
    private static final String START_POSE_TAG = "start_pose";
    private static final String STARTED_TICK_TAG = "started_tick";
    private static final String TARGET_FRAMES_TAG = "target_frames";
    private static final String TARGET_ORIGIN_TAG = "target_origin";
    private static final String FINAL_POSE_TAG = "final_pose";

    private final UUID assemblyId;
    private final Set<BlockPos> sourceFrames;
    private final BlockPos sourceOrigin;
    private final Set<BlockPos> localFrames;
    private final AssemblyPose startPose;
    private final long startedTick;
    private final Set<BlockPos> targetFrames;
    private final BlockPos targetOrigin;
    private final AssemblyPose finalPose;

    public PendingContraptionMove(
            UUID assemblyId,
            Collection<BlockPos> sourceFrames,
            BlockPos sourceOrigin,
            Collection<BlockPos> localFrames,
            AssemblyPose startPose,
            long startedTick) {
        this(assemblyId, sourceFrames, sourceOrigin, localFrames, startPose, startedTick, Set.of(), null, null);
    }

    private PendingContraptionMove(
            UUID assemblyId,
            Collection<BlockPos> sourceFrames,
            BlockPos sourceOrigin,
            Collection<BlockPos> localFrames,
            AssemblyPose startPose,
            long startedTick,
            Collection<BlockPos> targetFrames,
            BlockPos targetOrigin,
            AssemblyPose finalPose) {
        this.assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
        this.sourceFrames = immutableUnique(sourceFrames);
        this.sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin").immutable();
        this.localFrames = immutableUnique(localFrames);
        this.startPose = Objects.requireNonNull(startPose, "startPose");
        this.startedTick = startedTick;
        this.targetFrames = immutableUnique(targetFrames);
        this.targetOrigin = targetOrigin == null ? null : targetOrigin.immutable();
        this.finalPose = finalPose;

        if (this.sourceFrames.isEmpty()
                || this.sourceFrames.size() != sourceFrames.size()
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

    public PendingContraptionMove withPlacement(
            Collection<BlockPos> targetFrames,
            BlockPos targetOrigin,
            AssemblyPose finalPose) {
        return new PendingContraptionMove(
                assemblyId,
                sourceFrames,
                sourceOrigin,
                localFrames,
                startPose,
                startedTick,
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

    public BlockPos delta() {
        if (!hasPlacement()) {
            throw new IllegalStateException("Create placement has not been journaled");
        }
        return targetOrigin.subtract(sourceOrigin);
    }

    public boolean covers(BlockPos position) {
        return sourceFrames.contains(position) || targetFrames.contains(position);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putLongArray(SOURCE_FRAMES_TAG, sourceFrames.stream().map(BlockPos::asLong).toList());
        tag.putLong(SOURCE_ORIGIN_TAG, sourceOrigin.asLong());
        tag.putLongArray(LOCAL_FRAMES_TAG, localFrames.stream().map(BlockPos::asLong).toList());
        tag.put(START_POSE_TAG, startPose.save());
        tag.putLong(STARTED_TICK_TAG, startedTick);
        if (hasPlacement()) {
            tag.putLongArray(TARGET_FRAMES_TAG, targetFrames.stream().map(BlockPos::asLong).toList());
            tag.putLong(TARGET_ORIGIN_TAG, targetOrigin.asLong());
            tag.put(FINAL_POSE_TAG, finalPose.save());
        }
        return tag;
    }

    public static PendingContraptionMove load(CompoundTag tag) {
        if (!tag.hasUUID(ASSEMBLY_ID_TAG)
                || !tag.contains(SOURCE_ORIGIN_TAG)
                || !tag.contains(START_POSE_TAG, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Incomplete pending Create contraption journal");
        }
        Set<BlockPos> sources = positions(tag.getLongArray(SOURCE_FRAMES_TAG));
        Set<BlockPos> locals = positions(tag.getLongArray(LOCAL_FRAMES_TAG));
        BlockPos sourceOrigin = BlockPos.of(tag.getLong(SOURCE_ORIGIN_TAG));
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
                    sourceOrigin,
                    locals,
                    startPose,
                    tag.getLong(STARTED_TICK_TAG),
                    positions(tag.getLongArray(TARGET_FRAMES_TAG)),
                    targetOrigin,
                    AssemblyPose.load(tag.getCompound(FINAL_POSE_TAG), AssemblyPose.identityAt(targetOrigin)));
        }
        return new PendingContraptionMove(
                tag.getUUID(ASSEMBLY_ID_TAG),
                sources,
                sourceOrigin,
                locals,
                startPose,
                tag.getLong(STARTED_TICK_TAG));
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

    private static Set<BlockPos> immutableUnique(Collection<BlockPos> positions) {
        Set<BlockPos> result = new HashSet<>();
        positions.forEach(position -> result.add(position.immutable()));
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
