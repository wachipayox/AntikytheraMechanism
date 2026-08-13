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

/** Discrete physical-to-logical orientation for a complete MechanismAssembly. */
public record FrameOrientation(Direction up, Direction front) {
    private static final String UP_TAG = "up";
    private static final String FRONT_TAG = "front";
    private static final double SNAP_EPSILON = 1.0E-4;
    public static final FrameOrientation IDENTITY = new FrameOrientation(Direction.UP, Direction.NORTH);

    public FrameOrientation {
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(front, "front");
        if (up.getAxis() == front.getAxis()) throw new IllegalArgumentException("Frame orientation axes must be perpendicular");
    }

    public BlockPos toPhysical(BlockPos logical) {
        Direction right = right(), back = front.getOpposite();
        return new BlockPos(
                logical.getX() * right.getStepX() + logical.getY() * up.getStepX() + logical.getZ() * back.getStepX(),
                logical.getX() * right.getStepY() + logical.getY() * up.getStepY() + logical.getZ() * back.getStepY(),
                logical.getX() * right.getStepZ() + logical.getY() * up.getStepZ() + logical.getZ() * back.getStepZ());
    }

    public BlockPos toLogical(BlockPos physical) {
        Direction right = right(), back = front.getOpposite();
        return new BlockPos(dot(physical, right), dot(physical, up), dot(physical, back));
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
                px * right.getStepX() + py * right.getStepY() + pz * right.getStepZ() + .5,
                px * up.getStepX() + py * up.getStepY() + pz * up.getStepZ() + .5,
                px * back.getStepX() + py * back.getStepY() + pz * back.getStepZ() + .5);
    }

    /** Maps a continuous point in logical mini axes [0,1]^3 into the physical Frame cube. */
    public Vector3d logicalLocalToPhysical(double x, double y, double z, Vector3d destination) {
        Direction right = right(), back = front.getOpposite();
        double lx = x - .5, ly = y - .5, lz = z - .5;
        return destination.set(
                lx * right.getStepX() + ly * up.getStepX() + lz * back.getStepX() + .5,
                lx * right.getStepY() + ly * up.getStepY() + lz * back.getStepY() + .5,
                lx * right.getStepZ() + ly * up.getStepZ() + lz * back.getStepZ() + .5);
    }

    public Direction right() {
        int x = front.getStepY() * up.getStepZ() - front.getStepZ() * up.getStepY();
        int y = front.getStepZ() * up.getStepX() - front.getStepX() * up.getStepZ();
        int z = front.getStepX() * up.getStepY() - front.getStepY() * up.getStepX();
        Direction direction = Direction.fromDelta(x, y, z);
        if (direction == null) throw new IllegalStateException("Invalid frame orientation basis");
        return direction;
    }

    public boolean isUpright() { return up == Direction.UP; }

    public FrameOrientation rotate(Direction.Axis axis, int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        return new FrameOrientation(rotateDirection(up, axis, turns), rotateDirection(front, axis, turns));
    }

    public Quaterniond quaternion(Quaterniond destination) {
        Direction right = right(), back = front.getOpposite();
        org.joml.Matrix3d matrix = new org.joml.Matrix3d(
                right.getStepX(), right.getStepY(), right.getStepZ(),
                up.getStepX(), up.getStepY(), up.getStepZ(),
                back.getStepX(), back.getStepY(), back.getStepZ());
        return destination.setFromNormalized(matrix).normalize();
    }

    public static Optional<FrameOrientation> fromQuaternion(Quaterniondc quaternion) {
        Quaterniond normalized = new Quaterniond(quaternion).normalize();
        Direction up = snap(normalized.transform(new Vector3d(0, 1, 0)));
        Direction front = snap(normalized.transform(new Vector3d(0, 0, -1)));
        if (up == null || front == null || up.getAxis() == front.getAxis()) return Optional.empty();
        return Optional.of(new FrameOrientation(up, front));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(UP_TAG, up.ordinal());
        tag.putInt(FRONT_TAG, front.ordinal());
        return tag;
    }

    public static FrameOrientation load(CompoundTag tag) {
        if (!tag.contains(UP_TAG, Tag.TAG_ANY_NUMERIC) || !tag.contains(FRONT_TAG, Tag.TAG_ANY_NUMERIC)) return IDENTITY;
        Direction[] values = Direction.values();
        int upOrdinal = tag.getInt(UP_TAG), frontOrdinal = tag.getInt(FRONT_TAG);
        if (upOrdinal < 0 || upOrdinal >= values.length || frontOrdinal < 0 || frontOrdinal >= values.length) return IDENTITY;
        try { return new FrameOrientation(values[upOrdinal], values[frontOrdinal]); }
        catch (IllegalArgumentException ignored) { return IDENTITY; }
    }

    private static BlockPos step(Direction direction) {
        return new BlockPos(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static Direction snap(Vector3d vector) {
        int x = (int) Math.round(vector.x), y = (int) Math.round(vector.y), z = (int) Math.round(vector.z);
        if (Math.abs(vector.x - x) > SNAP_EPSILON || Math.abs(vector.y - y) > SNAP_EPSILON
                || Math.abs(vector.z - z) > SNAP_EPSILON || Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) return null;
        return Direction.fromDelta(x, y, z);
    }

    private static int dot(BlockPos position, Direction axis) {
        return position.getX() * axis.getStepX() + position.getY() * axis.getStepY() + position.getZ() * axis.getStepZ();
    }

    private static Direction rotateDirection(Direction direction, Direction.Axis axis, int turns) {
        int x = direction.getStepX(), y = direction.getStepY(), z = direction.getStepZ();
        for (int index = 0; index < turns; index++) {
            int oldX = x, oldY = y, oldZ = z;
            switch (axis) {
                case X -> { x = oldX; y = -oldZ; z = oldY; }
                case Y -> { x = -oldZ; y = oldY; z = oldX; }
                case Z -> { x = oldY; y = -oldX; z = oldZ; }
            }
        }
        Direction result = Direction.fromDelta(x, y, z);
        if (result == null) throw new IllegalStateException("Orthogonal rotation produced a non-cardinal direction");
        return result;
    }
}
