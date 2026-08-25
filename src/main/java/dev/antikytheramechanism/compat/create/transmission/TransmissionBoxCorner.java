package dev.antikytheramechanism.compat.create.transmission;

import net.minecraft.core.Direction;

public enum TransmissionBoxCorner {
    NNN(-1, -1, -1),
    NNP(-1, -1, 1),
    NPN(-1, 1, -1),
    NPP(-1, 1, 1),
    PNN(1, -1, -1),
    PNP(1, -1, 1),
    PPN(1, 1, -1),
    PPP(1, 1, 1);

    private final int x;
    private final int y;
    private final int z;

    TransmissionBoxCorner(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int sign(Direction.Axis axis) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    public int cell(Direction.Axis axis) {
        return sign(axis) > 0 ? 1 : 0;
    }

    public TransmissionBoxCorner rotateClockwise(Direction.Axis axis) {
        Direction xDirection = Direction.get(x > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, Direction.Axis.X)
                .getClockWise(axis);
        Direction yDirection = Direction.get(y > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, Direction.Axis.Y)
                .getClockWise(axis);
        Direction zDirection = Direction.get(z > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, Direction.Axis.Z)
                .getClockWise(axis);

        int rx = componentSign(Direction.Axis.X, xDirection, yDirection, zDirection);
        int ry = componentSign(Direction.Axis.Y, xDirection, yDirection, zDirection);
        int rz = componentSign(Direction.Axis.Z, xDirection, yDirection, zDirection);
        return fromSigns(rx, ry, rz);
    }

    public static TransmissionBoxCorner fromSigns(int x, int y, int z) {
        for (TransmissionBoxCorner corner : values()) {
            if (corner.x == Integer.signum(x)
                    && corner.y == Integer.signum(y)
                    && corner.z == Integer.signum(z)) {
                return corner;
            }
        }
        throw new IllegalArgumentException("Corner signs must all be non-zero");
    }

    private static int componentSign(Direction.Axis axis, Direction... directions) {
        for (Direction direction : directions) {
            if (direction.getAxis() == axis) {
                return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            }
        }
        throw new IllegalStateException("Rotated corner lost axis " + axis);
    }
}
