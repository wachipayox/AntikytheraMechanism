package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.Optional;

/**
 * Discrete static orientation for a complete MechanismAssembly.
 *
 * <p>A placed Mechanism Frame is always upright relative to its parent level, so the only persistent
 * discrete degree of freedom is horizontal yaw. Arbitrary pitch/roll belongs to {@link AssemblyPose}
 * while Create or another physical host is moving the assembly; it is never retained as hidden static
 * Frame state after placement.</p>
 */
public record FrameOrientation(Direction front) {
    private static final String LEGACY_UP_TAG = "up";
    private static final String FRONT_TAG = "front";
    private static final double SNAP_EPSILON = 1.0E-4;
    public static final FrameOrientation IDENTITY = new FrameOrientation(Direction.NORTH);

    public FrameOrientation {
        Objects.requireNonNull(front, "front");
        if (front.getAxis() == Direction.Axis.Y) {
            throw new IllegalArgumentException("Static Frame orientation must have a horizontal front");
        }
    }

    /**
     * Compatibility constructor for callers and persisted 24-way data from before static yaw became
     * canonical. The supplied up/front basis is collapsed onto the equivalent upright horizontal yaw;
     * the up direction is not stored.
     */
    public FrameOrientation(Direction legacyUp, Direction legacyFront) {
        this(canonicalFront(legacyUp, legacyFront));
    }

    /** Static Frames are always upright by definition. */
    public Direction up() {
        return Direction.UP;
    }

    public BlockPos toPhysical(BlockPos logical) {
        Direction right = right(), back = front.getOpposite();
        return new BlockPos(
                logical.getX() * right.getStepX() + logical.getY() * Direction.UP.getStepX()
                        + logical.getZ() * back.getStepX(),
                logical.getX() * right.getStepY() + logical.getY() * Direction.UP.getStepY()
                        + logical.getZ() * back.getStepY(),
                logical.getX() * right.getStepZ() + logical.getY() * Direction.UP.getStepZ()
                        + logical.getZ() * back.getStepZ());
    }

    public BlockPos toLogical(BlockPos physical) {
        Direction right = right(), back = front.getOpposite();
        return new BlockPos(dot(physical, right), dot(physical, Direction.UP), dot(physical, back));
    }

    public Direction toPhysical(Direction logical) {
        BlockPos mapped = toPhysical(step(logical));
        return Direction.fromDelta(mapped.getX(), mapped.getY(), mapped.getZ());
    }

    public Direction toLogical(Direction physical) {
        BlockPos mapped = toLogical(step(physical));
        return Direction.fromDelta(mapped.getX(), mapped.getY(), mapped.getZ());
    }

    /** Maps one 0/1 mini cell inside a physical Frame back to immutable logical cell coordinates. */
    public BlockPos physicalCellToLogical(int x, int y, int z) {
        BlockPos signedPhysical = new BlockPos(x * 2 - 1, y * 2 - 1, z * 2 - 1);
        BlockPos signedLogical = toLogical(signedPhysical);
        return new BlockPos((signedLogical.getX() + 1) / 2,
                (signedLogical.getY() + 1) / 2, (signedLogical.getZ() + 1) / 2);
    }

    /** Maps one immutable logical 0/1 mini cell to its physical quadrant inside the Frame. */
    public BlockPos logicalCellToPhysical(int x, int y, int z) {
        BlockPos signedLogical = new BlockPos(x * 2 - 1, y * 2 - 1, z * 2 - 1);
        BlockPos signedPhysical = toPhysical(signedLogical);
        return new BlockPos((signedPhysical.getX() + 1) / 2,
                (signedPhysical.getY() + 1) / 2, (signedPhysical.getZ() + 1) / 2);
    }

    /** Maps a continuous point in one physical Frame cube [0,1]^3 into logical mini axes. */
    public Vector3d physicalLocalToLogical(double x, double y, double z, Vector3d destination) {
        Direction right = right(), back = front.getOpposite();
        double px = x - .5, py = y - .5, pz = z - .5;
        return destination.set(
                px * right.getStepX() + py * Direction.UP.getStepX() + pz * back.getStepX() + .5,
                px * right.getStepY() + py * Direction.UP.getStepY() + pz * back.getStepY() + .5,
                px * right.getStepZ() + py * Direction.UP.getStepZ() + pz * back.getStepZ() + .5);
    }

    /** Maps a continuous point in logical mini axes [0,1]^3 into the physical Frame cube. */
    public Vector3d logicalLocalToPhysical(double x, double y, double z, Vector3d destination) {
        Direction right = right(), back = front.getOpposite();
        double lx = x - .5, ly = y - .5, lz = z - .5;
        return destination.set(
                lx * right.getStepX() + ly * Direction.UP.getStepX() + lz * back.getStepX() + .5,
                lx * right.getStepY() + ly * Direction.UP.getStepY() + lz * back.getStepY() + .5,
                lx * right.getStepZ() + ly * Direction.UP.getStepZ() + lz * back.getStepZ() + .5);
    }

    public Direction right() {
        return switch (front) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> throw new IllegalStateException("Static Frame front is not horizontal: " + front);
        };
    }

    public boolean isUpright() {
        return true;
    }

    /**
     * Applies an orthogonal rotation and immediately canonicalizes the result back to static yaw.
     * Continuous/in-flight pitch and roll must be represented by AssemblyPose instead of this value.
     */
    public FrameOrientation rotate(Direction.Axis axis, int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return this;
        }
        Direction rotatedUp = rotateDirection(Direction.UP, axis, turns);
        Direction rotatedFront = rotateDirection(front, axis, turns);
        return new FrameOrientation(rotatedUp, rotatedFront);
    }

    public Quaterniond quaternion(Quaterniond destination) {
        Direction right = right(), back = front.getOpposite();
        org.joml.Matrix3d matrix = new org.joml.Matrix3d(
                right.getStepX(), right.getStepY(), right.getStepZ(),
                Direction.UP.getStepX(), Direction.UP.getStepY(), Direction.UP.getStepZ(),
                back.getStepX(), back.getStepY(), back.getStepZ());
        return destination.setFromNormalized(matrix).normalize();
    }

    /**
     * Accepts any orthogonal quaternion but returns its canonical static yaw. This deliberately
     * discards pitch/roll only at the discrete Frame-orientation boundary; the original quaternion
     * remains available to AssemblyPose while the assembly is moving.
     */
    public static Optional<FrameOrientation> fromQuaternion(Quaterniondc quaternion) {
        Quaterniond normalized = new Quaterniond(quaternion).normalize();
        Direction up = snap(normalized.transform(new Vector3d(0, 1, 0)));
        Direction front = snap(normalized.transform(new Vector3d(0, 0, -1)));
        if (up == null || front == null || up.getAxis() == front.getAxis()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FrameOrientation(up, front));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** New saves contain only the horizontal yaw. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(FRONT_TAG, front.ordinal());
        return tag;
    }

    /** Loads both the new yaw-only format and legacy 24-way up/front data. */
    public static FrameOrientation load(CompoundTag tag) {
        if (!tag.contains(FRONT_TAG, Tag.TAG_ANY_NUMERIC)) {
            return IDENTITY;
        }
        Direction[] values = Direction.values();
        int frontOrdinal = tag.getInt(FRONT_TAG);
        if (frontOrdinal < 0 || frontOrdinal >= values.length) {
            return IDENTITY;
        }
        Direction storedFront = values[frontOrdinal];
        if (storedFront.getAxis() != Direction.Axis.Y) {
            return new FrameOrientation(storedFront);
        }
        if (!tag.contains(LEGACY_UP_TAG, Tag.TAG_ANY_NUMERIC)) {
            return IDENTITY;
        }
        int upOrdinal = tag.getInt(LEGACY_UP_TAG);
        if (upOrdinal < 0 || upOrdinal >= values.length) {
            return IDENTITY;
        }
        try {
            return new FrameOrientation(values[upOrdinal], storedFront);
        } catch (IllegalArgumentException ignored) {
            return IDENTITY;
        }
    }

    private static Direction canonicalFront(Direction legacyUp, Direction legacyFront) {
        Objects.requireNonNull(legacyUp, "legacyUp");
        Objects.requireNonNull(legacyFront, "legacyFront");
        if (legacyUp.getAxis() == legacyFront.getAxis()) {
            throw new IllegalArgumentException("Frame orientation axes must be perpendicular");
        }
        if (legacyFront.getAxis() != Direction.Axis.Y) {
            return legacyFront;
        }

        Direction right = cross(legacyFront, legacyUp);
        if (right == null || right.getAxis() == Direction.Axis.Y) {
            throw new IllegalArgumentException("Legacy Frame orientation cannot be canonicalized to yaw");
        }
        return switch (right) {
            case EAST -> Direction.NORTH;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case NORTH -> Direction.WEST;
            default -> throw new IllegalArgumentException("Legacy Frame right axis is not horizontal");
        };
    }

    private static Direction cross(Direction first, Direction second) {
        int x = first.getStepY() * second.getStepZ() - first.getStepZ() * second.getStepY();
        int y = first.getStepZ() * second.getStepX() - first.getStepX() * second.getStepZ();
        int z = first.getStepX() * second.getStepY() - first.getStepY() * second.getStepX();
        return Direction.fromDelta(x, y, z);
    }

    private static BlockPos step(Direction direction) {
        return new BlockPos(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static Direction snap(Vector3d vector) {
        int x = (int) Math.round(vector.x), y = (int) Math.round(vector.y), z = (int) Math.round(vector.z);
        if (Math.abs(vector.x - x) > SNAP_EPSILON || Math.abs(vector.y - y) > SNAP_EPSILON
                || Math.abs(vector.z - z) > SNAP_EPSILON || Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) {
            return null;
        }
        return Direction.fromDelta(x, y, z);
    }

    private static int dot(BlockPos position, Direction axis) {
        return position.getX() * axis.getStepX() + position.getY() * axis.getStepY()
                + position.getZ() * axis.getStepZ();
    }

    private static Direction rotateDirection(Direction direction, Direction.Axis axis, int turns) {
        int x = direction.getStepX(), y = direction.getStepY(), z = direction.getStepZ();
        for (int index = 0; index < turns; index++) {
            int oldX = x, oldY = y, oldZ = z;
            switch (axis) {
                case X -> {
                    x = oldX;
                    y = -oldZ;
                    z = oldY;
                }
                case Y -> {
                    x = -oldZ;
                    y = oldY;
                    z = oldX;
                }
                case Z -> {
                    x = oldY;
                    y = -oldX;
                    z = oldZ;
                }
            }
        }
        Direction result = Direction.fromDelta(x, y, z);
        if (result == null) {
            throw new IllegalStateException("Orthogonal rotation produced a non-cardinal direction");
        }
        return result;
    }
}
