package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.frame.FrameMask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public final class MechanismAssembly {
    private static final String ID_TAG = "id";
    private static final String SUBLEVEL_ID_TAG = "sublevel_id";
    private static final String ORIGIN_TAG = "origin";
    private static final String FRAMES_TAG = "frames";
    private static final String SERVICE_ANCHOR_TAG = "service_anchor";
    private static final String POSE_TARGET_TAG = "pose_target";

    private final UUID id;
    private FrameMask frameMask;
    private BlockPos origin;
    private UUID subLevelId;
    private BlockPos serviceAnchor;
    private AssemblyPose poseTarget;

    public MechanismAssembly(UUID id, BlockPos origin) {
        this(
                id,
                null,
                origin,
                Set.of(origin),
                new BlockPos(0, -1, 0),
                AssemblyPose.identityAt(origin));
    }

    public MechanismAssembly(UUID id, BlockPos origin, Collection<BlockPos> frames) {
        this(
                id,
                null,
                origin,
                frames,
                new BlockPos(0, -1, 0),
                AssemblyPose.identityAt(origin));
    }

    private MechanismAssembly(
            UUID id,
            UUID subLevelId,
            BlockPos origin,
            Collection<BlockPos> frames,
            BlockPos serviceAnchor,
            AssemblyPose poseTarget) {
        this.id = id;
        this.subLevelId = subLevelId;
        this.origin = origin.immutable();
        this.frameMask = new FrameMask(this.origin, frames);
        this.serviceAnchor = serviceAnchor.immutable();
        this.poseTarget = poseTarget;
    }

    public UUID id() {
        return id;
    }

    public UUID subLevelId() {
        return subLevelId;
    }

    public void setSubLevelId(UUID subLevelId) {
        this.subLevelId = subLevelId;
    }

    public BlockPos origin() {
        return origin;
    }

    public Set<BlockPos> frames() {
        return frameMask.frames();
    }

    public FrameMask frameMask() {
        return frameMask;
    }

    public boolean containsFrame(BlockPos pos) {
        return frameMask.containsFrame(pos);
    }

    public void addFrame(BlockPos pos) {
        frameMask.addFrame(pos);
    }

    public void addFrames(Collection<BlockPos> positions) {
        frameMask.addFrames(positions);
    }

    public void removeFrame(BlockPos pos) {
        frameMask.removeFrame(pos);
    }

    public void removeFrames(Collection<BlockPos> positions) {
        frameMask.removeFrames(positions);
    }

    /**
     * Rebases the parent-frame coordinates without touching any mini position.
     * Moving both the origin and every frame by the same amount keeps
     * {@code frame - origin}, and therefore every mapped mini BlockPos, invariant.
     */
    public void translate(BlockPos delta) {
        if (delta.equals(BlockPos.ZERO)) {
            return;
        }
        BlockPos translatedOrigin = origin.offset(delta).immutable();
        Set<BlockPos> translatedFrames = frames().stream()
                .map(frame -> frame.offset(delta).immutable())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        origin = translatedOrigin;
        frameMask = new FrameMask(translatedOrigin, translatedFrames);
    }

    public BlockPos serviceAnchor() {
        return serviceAnchor;
    }

    public void setServiceAnchor(BlockPos serviceAnchor) {
        this.serviceAnchor = serviceAnchor.immutable();
    }

    public AssemblyPose poseTarget() {
        return poseTarget;
    }

    public void setPoseTarget(AssemblyPose poseTarget) {
        this.poseTarget = java.util.Objects.requireNonNull(poseTarget, "poseTarget");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ID_TAG, id);
        if (subLevelId != null) {
            tag.putUUID(SUBLEVEL_ID_TAG, subLevelId);
        }
        tag.putLong(ORIGIN_TAG, origin.asLong());
        tag.putLongArray(FRAMES_TAG, frames().stream().map(BlockPos::asLong).toList());
        tag.putLong(SERVICE_ANCHOR_TAG, serviceAnchor.asLong());
        tag.put(POSE_TARGET_TAG, poseTarget.save());
        return tag;
    }

    public static MechanismAssembly load(CompoundTag tag) {
        Set<BlockPos> frames = new java.util.HashSet<>();
        for (long packedPos : tag.getLongArray(FRAMES_TAG)) {
            frames.add(BlockPos.of(packedPos));
        }
        BlockPos origin = BlockPos.of(tag.getLong(ORIGIN_TAG));
        AssemblyPose fallbackPose = AssemblyPose.identityAt(origin);
        AssemblyPose pose = tag.contains(POSE_TARGET_TAG, Tag.TAG_COMPOUND)
                ? AssemblyPose.load(tag.getCompound(POSE_TARGET_TAG), fallbackPose)
                : fallbackPose;
        return new MechanismAssembly(
                tag.getUUID(ID_TAG),
                tag.hasUUID(SUBLEVEL_ID_TAG) ? tag.getUUID(SUBLEVEL_ID_TAG) : null,
                origin,
                frames,
                BlockPos.of(tag.getLong(SERVICE_ANCHOR_TAG)),
                pose);
    }
}
