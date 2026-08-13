package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaterniond;

import java.util.Objects;

/**
 * Discrete physical-to-logical orientation of one complete MechanismAssembly.
 *
 * <p>Logical NORTH and UP define the immutable mini-world axes. Physical Frames may move and rotate
 * around those axes without rotating a single mini BlockPos or BlockState. The representation is
 * deliberately capable of all 24 orthogonal cube orientations even though Create compatibility
 * initially permits only upright (yaw-only) rotations.</p>
 */
public record FrameOrientation(Direction up, Direction front) {
    private static final String UP_TAG = "up";
    private static final String FRONT_TAG = "front";
    public static final FrameOrientation IDENTITY = new FrameOrientation(Direction.UP, Direction.NORTH);

    public FrameOrientation {
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(front, "front");
        if (up.getAxis() == front.getAxis()) {
            throw new IllegalArgumentException("Frame orientation UP and FRONT must be perpendicular");
        }
    }

    public BlockPos toPhysical(BlockPos logical) {
        Direction right = right();
        Direction back = front.getOpposite();
        return new BlockPos(
                logical.getX() * right.getStepX() + logical.getY() * up.getStepX() + logical.getZ() * back.getStepX(),
                logical.getX() * right.getStepY() + logical.getY() * up.getStepY() + logical.getZ() * back.getStepY(),
                logical.getX() * right.getStepZ() + logical.getY() * up.getStepZ() + logical.getZ() * back.getStepZ());
    }

    public BlockPos toLogical(BlockPos physical) {
        Direction right = right();
        Direction back = front.getOpposite();
        return new BlockPos(dot(physical, right), dot(physical, up), dot(physical, back));
    }

    public Direction toPhysical(Direction logical) {
        BlockPos mapped = toPhysical(new BlockPos(logical.getStepX(), logical.getStepY(), logical.getStepZ()));
        return Direction.fromDelta(mapped.getX(), mapped.getY(), mapped.getZ());
    }

    public Direction toLogical(Direction physical) {
        BlockPos mapped = toLogical(new BlockPos(physical.getStepX(), physical.getStepY(), physical.getStepZ()));
        return Direction.fromDelta(mapped.getX(), mapped.getY(), mapped.getZ());
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
        Direction right = right();
        Direction back = front.getOpposite();
        org.joml.Matrix3d matrix = new org.joml.Matrix3d(
                right.getStepX(), right.getStepY(), right.getStepZ(),
                up.getStepX(), up.getStepY(), up.getStepZ(),
                back.getStepX(), back.getStepY(), back.getStepZ());
        return destination.setFromNormalized(matrix).normalize();
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
        int upOrdinal = tag.getInt(UP_TAG);
        int frontOrdinal = tag.getInt(FRONT_TAG);
        if (upOrdinal < 0 || upOrdinal >= values.length || frontOrdinal < 0 || frontOrdinal >= values.length) return IDENTITY;
        try {
            return new FrameOrientation(values[upOrdinal], values[frontOrdinal]);
        } catch (IllegalArgumentException ignored) {
            return IDENTITY;
        }
    }

    public static int quarterTurns(int degrees) {
        if (degrees % 90 != 0) throw new IllegalArgumentException("Frame rotation must be a multiple of 90 degrees");
        return Math.floorMod(degrees / 90, 4);
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
