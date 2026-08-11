package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted journal entry for one complete assembly carried by a vanilla piston.
 *
 * <p>The journal deliberately contains no Sable/native handles. The mini plot is
 * never rewritten; only the semantic world pose and the parent-frame index move.</p>
 */
public final class PendingPistonMove {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String PISTON_POSITION_TAG = "piston_position";
    private static final String DELTA_TAG = "delta";
    private static final String SOURCE_FRAMES_TAG = "source_frames";
    private static final String START_POSE_TAG = "start_pose";
    private static final String STARTED_TICK_TAG = "started_tick";
    private static final String EXTENDING_TAG = "extending";

    private final UUID assemblyId;
    private final BlockPos pistonPosition;
    private final BlockPos delta;
    private final Set<BlockPos> sourceFrames;
    private final Set<BlockPos> destinationFrames;
    private final AssemblyPose startPose;
    private final long startedTick;
    private final boolean extending;

    public PendingPistonMove(
            UUID assemblyId,
            BlockPos pistonPosition,
            BlockPos delta,
            Collection<BlockPos> sourceFrames,
            AssemblyPose startPose,
            long startedTick,
            boolean extending) {
        this.assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
        this.pistonPosition = Objects.requireNonNull(pistonPosition, "pistonPosition").immutable();
        this.delta = Objects.requireNonNull(delta, "delta").immutable();
        this.startPose = Objects.requireNonNull(startPose, "startPose");
        this.startedTick = startedTick;
        this.extending = extending;

        int taxicabLength = Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ());
        if (taxicabLength != 1) {
            throw new IllegalArgumentException("A piston move must translate exactly one block: " + delta);
        }
        if (sourceFrames.isEmpty()) {
            throw new IllegalArgumentException("A piston move cannot have an empty frame set");
        }

        Set<BlockPos> immutableSources = new HashSet<>();
        Set<BlockPos> immutableDestinations = new HashSet<>();
        for (BlockPos source : sourceFrames) {
            BlockPos immutableSource = source.immutable();
            if (!immutableSources.add(immutableSource)) {
                throw new IllegalArgumentException("Duplicate piston source frame " + immutableSource);
            }
            immutableDestinations.add(immutableSource.offset(delta).immutable());
        }
        if (immutableDestinations.size() != immutableSources.size()) {
            throw new IllegalArgumentException("Piston frame translation is not one-to-one");
        }
        this.sourceFrames = Collections.unmodifiableSet(immutableSources);
        this.destinationFrames = Collections.unmodifiableSet(immutableDestinations);
    }

    public UUID assemblyId() {
        return assemblyId;
    }

    public BlockPos pistonPosition() {
        return pistonPosition;
    }

    public BlockPos delta() {
        return delta;
    }

    public Direction movementDirection() {
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == delta.getX()
                    && direction.getStepY() == delta.getY()
                    && direction.getStepZ() == delta.getZ()) {
                return direction;
            }
        }
        throw new IllegalStateException("Invalid persisted piston delta " + delta);
    }

    public Set<BlockPos> sourceFrames() {
        return sourceFrames;
    }

    public Set<BlockPos> destinationFrames() {
        return destinationFrames;
    }

    public AssemblyPose startPose() {
        return startPose;
    }

    public long startedTick() {
        return startedTick;
    }

    public boolean extending() {
        return extending;
    }

    /**
     * Identifies the vanilla carrier belonging to this journal without relying
     * only on its position. During sticky retraction the old piston-head carrier
     * briefly occupies a frame destination, but it is a source carrier and must
     * never advance or cancel the frame journal.
     */
    public boolean matchesCarrierMetadata(
            BlockPos carrierPosition,
            Direction carrierMovementDirection,
            boolean carrierExtending,
            boolean sourcePiston) {
        return destinationFrames.contains(carrierPosition)
                && !sourcePiston
                && carrierExtending == extending
                && carrierMovementDirection == movementDirection();
    }

    public AssemblyPose poseAtProgress(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return startPose.translated(new Vector3d(
                delta.getX() * clamped,
                delta.getY() * clamped,
                delta.getZ() * clamped));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putLong(PISTON_POSITION_TAG, pistonPosition.asLong());
        tag.putLong(DELTA_TAG, delta.asLong());
        tag.putLongArray(SOURCE_FRAMES_TAG, sourceFrames.stream().map(BlockPos::asLong).toList());
        tag.put(START_POSE_TAG, startPose.save());
        tag.putLong(STARTED_TICK_TAG, startedTick);
        tag.putBoolean(EXTENDING_TAG, extending);
        return tag;
    }

    public static PendingPistonMove load(CompoundTag tag) {
        if (!tag.hasUUID(ASSEMBLY_ID_TAG) || !tag.contains(START_POSE_TAG, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Incomplete pending piston move journal");
        }
        Set<BlockPos> sources = new HashSet<>();
        for (long packed : tag.getLongArray(SOURCE_FRAMES_TAG)) {
            sources.add(BlockPos.of(packed));
        }
        CompoundTag poseTag = tag.getCompound(START_POSE_TAG);
        if (!poseTag.contains("anchor_x")
                || !poseTag.contains("anchor_y")
                || !poseTag.contains("anchor_z")
                || !poseTag.contains("quaternion_x")
                || !poseTag.contains("quaternion_y")
                || !poseTag.contains("quaternion_z")
                || !poseTag.contains("quaternion_w")) {
            throw new IllegalArgumentException("Incomplete start pose in pending piston journal");
        }
        AssemblyPose startPose = new AssemblyPose(
                poseTag.getDouble("anchor_x"),
                poseTag.getDouble("anchor_y"),
                poseTag.getDouble("anchor_z"),
                poseTag.getDouble("quaternion_x"),
                poseTag.getDouble("quaternion_y"),
                poseTag.getDouble("quaternion_z"),
                poseTag.getDouble("quaternion_w"));
        return new PendingPistonMove(
                tag.getUUID(ASSEMBLY_ID_TAG),
                BlockPos.of(tag.getLong(PISTON_POSITION_TAG)),
                BlockPos.of(tag.getLong(DELTA_TAG)),
                sources,
                startPose,
                tag.getLong(STARTED_TICK_TAG),
                tag.getBoolean(EXTENDING_TAG));
    }
}
