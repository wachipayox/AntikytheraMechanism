package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** One Create kinetic node with configurable macro/micro faces and eight micro-cog corner ports. */
public final class TransmissionBoxBlockEntity extends KineticBlockEntity {
    private static final String FACES_TAG = "Faces";
    private static final String CORNERS_TAG = "Corners";

    private final EnumMap<Direction, TransmissionBoxFaceMode> faceModes = new EnumMap<>(Direction.class);
    private final EnumMap<TransmissionBoxCorner, TransmissionBoxCogMode> cornerModes =
            new EnumMap<>(TransmissionBoxCorner.class);

    public TransmissionBoxBlockEntity(BlockPos pos, BlockState state) {
        super(CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get(), pos, state);
        resetConfiguration();
    }

    public Direction.Axis structuralAxis() {
        return getBlockState().getValue(TransmissionBoxBlock.AXIS);
    }

    public TransmissionBoxFaceMode faceMode(Direction face) {
        if (face.getAxis() == structuralAxis()) {
            return TransmissionBoxFaceMode.CLOSED;
        }
        return faceModes.getOrDefault(face, TransmissionBoxFaceMode.CLOSED);
    }

    public TransmissionBoxCogMode cornerMode(TransmissionBoxCorner corner) {
        return cornerModes.getOrDefault(corner, TransmissionBoxCogMode.EMPTY);
    }

    public boolean cycleFace(Direction face) {
        if (face.getAxis() == structuralAxis()) {
            return false;
        }
        TransmissionBoxFaceMode current = faceMode(face);
        TransmissionBoxFaceMode next = switch (current) {
            case CLOSED -> TransmissionBoxFaceMode.MICRO;
            case MICRO -> canBecomeMacro(face)
                    ? TransmissionBoxFaceMode.MACRO
                    : TransmissionBoxFaceMode.CLOSED;
            case MACRO -> TransmissionBoxFaceMode.CLOSED;
        };
        mutateTopology(() -> faceModes.put(face, next));
        return next != current;
    }

    /**
     * Advances through cog modes until the first physically valid state is found. A blocked mode is
     * therefore skipped rather than cancelling the whole wrench click; client feedback still pulses
     * for the skipped proposal and the cogs that prevented it.
     */
    public boolean cycleCorner(TransmissionBoxCorner corner) {
        TransmissionBoxCogMode current = cornerMode(corner);
        TransmissionBoxCogMode candidate = current.next();
        for (int attempts = 0; attempts < TransmissionBoxCogMode.values().length; attempts++) {
            if (blockingCorners(corner, candidate).isEmpty()) {
                TransmissionBoxCogMode resolved = candidate;
                if (resolved == current) {
                    return false;
                }
                mutateTopology(() -> cornerModes.put(corner, resolved));
                return true;
            }
            candidate = candidate.next();
        }
        return false;
    }

    /** Blockers for the immediate next mode, used for the red skipped-state feedback. */
    public Set<TransmissionBoxCorner> blockersForNextCornerMode(TransmissionBoxCorner corner) {
        return blockingCorners(corner, cornerMode(corner).next());
    }

    public Set<TransmissionBoxCorner> blockingCorners(
            TransmissionBoxCorner corner,
            TransmissionBoxCogMode proposedMode) {
        EnumSet<TransmissionBoxCorner> blockers = EnumSet.noneOf(TransmissionBoxCorner.class);
        if (proposedMode == TransmissionBoxCogMode.EMPTY) {
            return blockers;
        }

        Direction.Axis planeAxis = structuralAxis();
        for (TransmissionBoxCorner other : TransmissionBoxCorner.values()) {
            if (other == corner || !adjacentOnCogPlane(corner, other, planeAxis)) {
                continue;
            }
            TransmissionBoxCogMode otherMode = cornerMode(other);
            if (otherMode == TransmissionBoxCogMode.EMPTY) {
                continue;
            }
            if (proposedMode == TransmissionBoxCogMode.LARGE
                    || otherMode == TransmissionBoxCogMode.LARGE) {
                blockers.add(other);
            }
        }
        return blockers;
    }

    public boolean canBecomeMacro(Direction face) {
        if (face.getAxis() == structuralAxis()) {
            return false;
        }
        Direction otherMacro = null;
        int count = 0;
        for (Direction direction : Direction.values()) {
            if (direction == face || faceMode(direction) != TransmissionBoxFaceMode.MACRO) {
                continue;
            }
            otherMacro = direction;
            count++;
        }
        return count == 0 || count == 1 && otherMacro == face.getOpposite();
    }

    /**
     * Create Gearbox-compatible sign convention over the four faces perpendicular to the structural
     * axis. The actual macro/micro ratio is applied separately by CreateTransmissionKineticBridge.
     */
    public int sideSign(Direction face) {
        if (face.getAxis() == structuralAxis()) {
            return 0;
        }
        Direction.Axis first = firstActiveAxis(structuralAxis());
        int sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        return face.getAxis() == first ? sign : -sign;
    }

    /** Rotates all stored ports with the same clockwise transform Create applies to the block. */
    public void rotateConfiguration(Direction.Axis rotationAxis) {
        EnumMap<Direction, TransmissionBoxFaceMode> rotatedFaces = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, TransmissionBoxFaceMode> entry : faceModes.entrySet()) {
            rotatedFaces.put(entry.getKey().getClockWise(rotationAxis), entry.getValue());
        }
        EnumMap<TransmissionBoxCorner, TransmissionBoxCogMode> rotatedCorners =
                new EnumMap<>(TransmissionBoxCorner.class);
        for (Map.Entry<TransmissionBoxCorner, TransmissionBoxCogMode> entry : cornerModes.entrySet()) {
            rotatedCorners.put(entry.getKey().rotateClockwise(rotationAxis), entry.getValue());
        }
        faceModes.clear();
        faceModes.putAll(rotatedFaces);
        cornerModes.clear();
        cornerModes.putAll(rotatedCorners);
    }

    public void beginTopologyMutation() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (hasSource() || isSource() || hasNetwork()) {
            detachKinetics();
        }
        removeSource();
        clearKineticInformation();
    }

    public void finishTopologyMutation() {
        if (level != null && !level.isClientSide) {
            updateSpeed = true;
        }
        setChanged();
        sendData();
    }

    private void mutateTopology(Runnable mutation) {
        beginTopologyMutation();
        mutation.run();
        finishTopologyMutation();
    }

    private void resetConfiguration() {
        faceModes.clear();
        cornerModes.clear();
        for (Direction direction : Direction.values()) {
            faceModes.put(direction, TransmissionBoxFaceMode.CLOSED);
        }
        for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
            cornerModes.put(corner, TransmissionBoxCogMode.EMPTY);
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        CompoundTag faces = new CompoundTag();
        for (Direction direction : Direction.values()) {
            faces.putByte(direction.getName(), (byte) faceModes.getOrDefault(
                    direction, TransmissionBoxFaceMode.CLOSED).ordinal());
        }
        compound.put(FACES_TAG, faces);

        CompoundTag corners = new CompoundTag();
        for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
            corners.putByte(corner.name(), (byte) cornerMode(corner).ordinal());
        }
        compound.put(CORNERS_TAG, corners);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        resetConfiguration();
        if (compound.contains(FACES_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag faces = compound.getCompound(FACES_TAG);
            for (Direction direction : Direction.values()) {
                if (faces.contains(direction.getName(), Tag.TAG_ANY_NUMERIC)) {
                    faceModes.put(direction, faceModeByOrdinal(faces.getByte(direction.getName())));
                }
            }
        }
        if (compound.contains(CORNERS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag corners = compound.getCompound(CORNERS_TAG);
            for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
                if (corners.contains(corner.name(), Tag.TAG_ANY_NUMERIC)) {
                    cornerModes.put(corner, cogModeByOrdinal(corners.getByte(corner.name())));
                }
            }
        }
        sanitizeConfiguration();
    }

    private void sanitizeConfiguration() {
        Direction.Axis axis = structuralAxis();
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == axis) {
                faceModes.put(direction, TransmissionBoxFaceMode.CLOSED);
            }
        }
        Direction firstMacro = null;
        for (Direction direction : Direction.values()) {
            if (faceModes.get(direction) != TransmissionBoxFaceMode.MACRO) {
                continue;
            }
            if (firstMacro == null) {
                firstMacro = direction;
                continue;
            }
            if (direction != firstMacro.getOpposite()) {
                faceModes.put(direction, TransmissionBoxFaceMode.CLOSED);
            }
        }

        // Worlds produced by the first experimental build may already contain impossible cog layouts.
        // Prefer keeping LARGE cogs, then fill every compatible SMALL cog around them deterministically.
        EnumMap<TransmissionBoxCorner, TransmissionBoxCogMode> loadedCorners =
                new EnumMap<>(cornerModes);
        for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
            cornerModes.put(corner, TransmissionBoxCogMode.EMPTY);
        }
        for (TransmissionBoxCogMode mode : new TransmissionBoxCogMode[] {
                TransmissionBoxCogMode.LARGE, TransmissionBoxCogMode.SMALL}) {
            for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
                if (loadedCorners.getOrDefault(corner, TransmissionBoxCogMode.EMPTY) != mode) {
                    continue;
                }
                if (blockingCorners(corner, mode).isEmpty()) {
                    cornerModes.put(corner, mode);
                }
            }
        }
    }

    private static boolean adjacentOnCogPlane(
            TransmissionBoxCorner first,
            TransmissionBoxCorner second,
            Direction.Axis planeAxis) {
        if (first.sign(planeAxis) != second.sign(planeAxis)) {
            return false;
        }
        int differences = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != planeAxis && first.sign(axis) != second.sign(axis)) {
                differences++;
            }
        }
        return differences == 1;
    }

    private static TransmissionBoxFaceMode faceModeByOrdinal(int ordinal) {
        TransmissionBoxFaceMode[] values = TransmissionBoxFaceMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TransmissionBoxFaceMode.CLOSED;
    }

    private static TransmissionBoxCogMode cogModeByOrdinal(int ordinal) {
        TransmissionBoxCogMode[] values = TransmissionBoxCogMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TransmissionBoxCogMode.EMPTY;
    }

    private static Direction.Axis firstActiveAxis(Direction.Axis structural) {
        return switch (structural) {
            case X -> Direction.Axis.Y;
            case Y -> Direction.Axis.X;
            case Z -> Direction.Axis.X;
        };
    }

    @Override
    protected boolean isNoisy() {
        return false;
    }
}
