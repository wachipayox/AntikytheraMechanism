package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent, Create-independent transform binding for one assembly actor.
 *
 * <p>The binding maps the center of a deterministic leader frame to the
 * semantic origin of the Sable assembly. It never transforms mini positions or
 * internal block states.</p>
 */
public record ContraptionPoseBinding(
        UUID assemblyId,
        BlockPos leaderLocalPosition,
        double localOffsetX,
        double localOffsetY,
        double localOffsetZ,
        double correctionX,
        double correctionY,
        double correctionZ,
        double correctionW) {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String LEADER_POSITION_TAG = "leader_local_position";
    private static final String LOCAL_OFFSET_X_TAG = "local_offset_x";
    private static final String LOCAL_OFFSET_Y_TAG = "local_offset_y";
    private static final String LOCAL_OFFSET_Z_TAG = "local_offset_z";
    private static final String CORRECTION_X_TAG = "correction_x";
    private static final String CORRECTION_Y_TAG = "correction_y";
    private static final String CORRECTION_Z_TAG = "correction_z";
    private static final String CORRECTION_W_TAG = "correction_w";
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-20;

    public ContraptionPoseBinding {
        Objects.requireNonNull(assemblyId, "assemblyId");
        leaderLocalPosition = Objects.requireNonNull(leaderLocalPosition, "leaderLocalPosition").immutable();
        if (!Double.isFinite(localOffsetX)
                || !Double.isFinite(localOffsetY)
                || !Double.isFinite(localOffsetZ)
                || !Double.isFinite(correctionX)
                || !Double.isFinite(correctionY)
                || !Double.isFinite(correctionZ)
                || !Double.isFinite(correctionW)) {
            throw new IllegalArgumentException("Contraption pose binding components must be finite");
        }
        double lengthSquared = correctionX * correctionX
                + correctionY * correctionY
                + correctionZ * correctionZ
                + correctionW * correctionW;
        if (lengthSquared < MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException("Contraption pose correction must be non-zero");
        }
        if (Math.abs(lengthSquared - 1.0) > 1.0E-12) {
            double inverseLength = 1.0 / Math.sqrt(lengthSquared);
            correctionX *= inverseLength;
            correctionY *= inverseLength;
            correctionZ *= inverseLength;
            correctionW *= inverseLength;
        }
    }

    /**
     * Creates a binding at initial assembly time. Create's captured local axes
     * are the parent world's axes before the contraption starts rotating.
     */
    public static Optional<ContraptionPoseBinding> initial(
            MechanismAssembly assembly,
            Collection<BlockPos> capturedLocalFrames,
            BlockPos leaderLocalPosition) {
        Optional<BlockPos> translation = findTranslation(capturedLocalFrames, assembly.frames());
        if (translation.isEmpty()) {
            return Optional.empty();
        }
        BlockPos localOrigin = assembly.origin().subtract(translation.get());
        BlockPos localOffset = localOrigin.subtract(leaderLocalPosition);
        Quaterniond correction = assembly.poseTarget().orientation(new Quaterniond());
        return Optional.of(of(assembly.id(), leaderLocalPosition, localOffset, correction));
    }

    /**
     * Reconstructs missing actor data after a reload. The current assembly pose
     * supplies the world orientation, so no assumption about the entity's
     * current rotation is needed.
     */
    public static Optional<ContraptionPoseBinding> rebind(
            MechanismAssembly assembly,
            Collection<BlockPos> capturedLocalFrames,
            BlockPos leaderLocalPosition,
            Quaterniondc currentContraptionRotation) {
        Optional<BlockPos> translation = findTranslation(capturedLocalFrames, assembly.frames());
        if (translation.isEmpty()) {
            return Optional.empty();
        }
        BlockPos localOrigin = assembly.origin().subtract(translation.get());
        BlockPos localOffset = localOrigin.subtract(leaderLocalPosition);
        Quaterniond correction = new Quaterniond(currentContraptionRotation)
                .normalize()
                .conjugate()
                .mul(assembly.poseTarget().orientation(new Quaterniond()))
                .normalize();
        return Optional.of(of(assembly.id(), leaderLocalPosition, localOffset, correction));
    }

    private static ContraptionPoseBinding of(
            UUID assemblyId,
            BlockPos leaderLocalPosition,
            BlockPos localOffset,
            Quaterniondc correction) {
        return new ContraptionPoseBinding(
                assemblyId,
                leaderLocalPosition,
                localOffset.getX(),
                localOffset.getY(),
                localOffset.getZ(),
                correction.x(),
                correction.y(),
                correction.z(),
                correction.w());
    }

    /**
     * Returns the sole translation mapping the captured local frame shape to
     * the manager's complete parent-world frame shape.
     */
    public static Optional<BlockPos> findTranslation(
            Collection<BlockPos> capturedLocalFrames,
            Collection<BlockPos> assemblyFrames) {
        Set<BlockPos> local = immutableUnique(capturedLocalFrames);
        Set<BlockPos> world = immutableUnique(assemblyFrames);
        if (local.isEmpty() || local.size() != capturedLocalFrames.size() || world.size() != assemblyFrames.size()
                || local.size() != world.size()) {
            return Optional.empty();
        }

        BlockPos localMinimum = local.stream().min(POSITION_ORDER).orElseThrow();
        BlockPos worldMinimum = world.stream().min(POSITION_ORDER).orElseThrow();
        BlockPos translation = worldMinimum.subtract(localMinimum);
        boolean sameShape = local.stream()
                .map(position -> position.offset(translation))
                .allMatch(world::contains);
        return sameShape ? Optional.of(translation.immutable()) : Optional.empty();
    }

    private static Set<BlockPos> immutableUnique(Collection<BlockPos> positions) {
        Set<BlockPos> result = new HashSet<>();
        positions.forEach(position -> result.add(position.immutable()));
        return result;
    }

    public AssemblyPose poseAt(Vector3dc leaderWorldCenter, Quaterniondc contraptionRotation) {
        Quaterniond normalizedRotation = new Quaterniond(contraptionRotation).normalize();
        Vector3d rotatedOffset = new Vector3d(localOffsetX, localOffsetY, localOffsetZ);
        normalizedRotation.transform(rotatedOffset);
        Vector3d anchor = new Vector3d(leaderWorldCenter).add(rotatedOffset);
        Quaterniond orientation = normalizedRotation.mul(correction(new Quaterniond())).normalize();
        return AssemblyPose.of(anchor, orientation);
    }

    public Quaterniond correction(Quaterniond destination) {
        return destination.set(correctionX, correctionY, correctionZ, correctionW);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putLong(LEADER_POSITION_TAG, leaderLocalPosition.asLong());
        tag.putDouble(LOCAL_OFFSET_X_TAG, localOffsetX);
        tag.putDouble(LOCAL_OFFSET_Y_TAG, localOffsetY);
        tag.putDouble(LOCAL_OFFSET_Z_TAG, localOffsetZ);
        tag.putDouble(CORRECTION_X_TAG, correctionX);
        tag.putDouble(CORRECTION_Y_TAG, correctionY);
        tag.putDouble(CORRECTION_Z_TAG, correctionZ);
        tag.putDouble(CORRECTION_W_TAG, correctionW);
        return tag;
    }

    public static Optional<ContraptionPoseBinding> load(CompoundTag tag) {
        if (!tag.hasUUID(ASSEMBLY_ID_TAG)
                || !tag.contains(LEADER_POSITION_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(LOCAL_OFFSET_X_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(LOCAL_OFFSET_Y_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(LOCAL_OFFSET_Z_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(CORRECTION_X_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(CORRECTION_Y_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(CORRECTION_Z_TAG, Tag.TAG_ANY_NUMERIC)
                || !tag.contains(CORRECTION_W_TAG, Tag.TAG_ANY_NUMERIC)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ContraptionPoseBinding(
                    tag.getUUID(ASSEMBLY_ID_TAG),
                    BlockPos.of(tag.getLong(LEADER_POSITION_TAG)),
                    tag.getDouble(LOCAL_OFFSET_X_TAG),
                    tag.getDouble(LOCAL_OFFSET_Y_TAG),
                    tag.getDouble(LOCAL_OFFSET_Z_TAG),
                    tag.getDouble(CORRECTION_X_TAG),
                    tag.getDouble(CORRECTION_Y_TAG),
                    tag.getDouble(CORRECTION_Z_TAG),
                    tag.getDouble(CORRECTION_W_TAG)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getZ);
}
