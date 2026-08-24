package dev.antikytheramechanism.compat.create.transmission;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Shared server/client interpretation of which configurable region a wrench is pointing at. */
public record TransmissionBoxHitTarget(
        Kind kind,
        Direction face,
        @Nullable TransmissionBoxCorner corner) {
    private static final double CORNER_EDGE = 0.22;
    private static final double FACE_MIN = 0.27;
    private static final double FACE_MAX = 0.73;

    public enum Kind {
        CORNER,
        FACE,
        ROTATE,
        NONE
    }

    public static TransmissionBoxHitTarget resolve(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());

        Direction.Axis first = firstTangent(face.getAxis());
        Direction.Axis second = secondTangent(face.getAxis());
        double firstValue = coordinate(local, first);
        double secondValue = coordinate(local, second);

        if (isCornerCoordinate(firstValue) && isCornerCoordinate(secondValue)) {
            int x = face.getAxis() == Direction.Axis.X
                    ? axisSign(face)
                    : coordinate(local, Direction.Axis.X) >= 0.5 ? 1 : -1;
            int y = face.getAxis() == Direction.Axis.Y
                    ? axisSign(face)
                    : coordinate(local, Direction.Axis.Y) >= 0.5 ? 1 : -1;
            int z = face.getAxis() == Direction.Axis.Z
                    ? axisSign(face)
                    : coordinate(local, Direction.Axis.Z) >= 0.5 ? 1 : -1;
            return new TransmissionBoxHitTarget(
                    Kind.CORNER,
                    face,
                    TransmissionBoxCorner.fromSigns(x, y, z));
        }

        if (face.getAxis() != box.structuralAxis()
                && inFaceCenter(firstValue)
                && inFaceCenter(secondValue)) {
            return new TransmissionBoxHitTarget(Kind.FACE, face, null);
        }

        if (box.faceMode(face) == TransmissionBoxFaceMode.CLOSED) {
            return new TransmissionBoxHitTarget(Kind.ROTATE, face, null);
        }
        return new TransmissionBoxHitTarget(Kind.NONE, face, null);
    }

    private static boolean isCornerCoordinate(double value) {
        return value <= CORNER_EDGE || value >= 1.0 - CORNER_EDGE;
    }

    private static boolean inFaceCenter(double value) {
        return value >= FACE_MIN && value <= FACE_MAX;
    }

    private static int axisSign(Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
    }

    private static double coordinate(Vec3 local, Direction.Axis axis) {
        return switch (axis) {
            case X -> local.x;
            case Y -> local.y;
            case Z -> local.z;
        };
    }

    private static Direction.Axis firstTangent(Direction.Axis normal) {
        return switch (normal) {
            case X -> Direction.Axis.Y;
            case Y -> Direction.Axis.X;
            case Z -> Direction.Axis.X;
        };
    }

    private static Direction.Axis secondTangent(Direction.Axis normal) {
        return switch (normal) {
            case X -> Direction.Axis.Z;
            case Y -> Direction.Axis.Z;
            case Z -> Direction.Axis.Y;
        };
    }
}
