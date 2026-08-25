package dev.antikytheramechanism.compat.create.transmission;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Shared server/client interpretation of configurable wrench and cog-placement regions. */
public record TransmissionBoxHitTarget(
        Kind kind,
        Direction face,
        @Nullable TransmissionBoxCorner corner) {
    private static final double CORNER_TANGENTIAL_EDGE = 0.25;
    private static final double CORNER_AXIS_DEPTH = 0.38;
    private static final double SMALL_COG_PLACEMENT_HALF_EXTENT = 0.22;
    private static final double LARGE_COG_PLACEMENT_HALF_EXTENT = 0.25;
    private static final double FACE_MIN = 0.27;
    private static final double FACE_MAX = 0.73;

    public enum Kind {
        CORNER,
        FACE,
        ROTATE,
        NONE
    }

    /**
     * Resolves only the configuration/wrench target. Corner selectors intentionally keep one fixed
     * volume regardless of whether that corner is EMPTY, SMALL or LARGE; a rendered cog must never
     * make the wrench selector grow and steal clicks from neighboring face regions.
     */
    public static TransmissionBoxHitTarget resolve(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Direction.Axis structuralAxis = box.structuralAxis();

        Direction.Axis first = firstTangent(face.getAxis());
        Direction.Axis second = secondTangent(face.getAxis());
        double firstValue = coordinate(local, first);
        double secondValue = coordinate(local, second);

        if (isCornerCoordinate(firstValue, first, structuralAxis)
                && isCornerCoordinate(secondValue, second, structuralAxis)) {
            return new TransmissionBoxHitTarget(
                    Kind.CORNER,
                    face,
                    cornerFromHit(local, face));
        }

        // The four faces perpendicular to the structural axis are configuration-only. No point on
        // one of those faces is ever interpreted as a whole-box rotation request.
        if (face.getAxis() != structuralAxis) {
            if (inFaceCenter(firstValue) && inFaceCenter(secondValue)) {
                return new TransmissionBoxHitTarget(Kind.FACE, face, null);
            }
            return new TransmissionBoxHitTarget(Kind.NONE, face, null);
        }

        // Axial faces cannot expose macro or micro shafts. Outside their corner selectors they are
        // deliberately left to the ordinary Create-style rotation interaction.
        return new TransmissionBoxHitTarget(Kind.ROTATE, face, null);
    }

    /**
     * Separately resolves the larger, visually centred source region used only by Create placement
     * helpers. Keeping this independent from {@link #resolve} lets SMALL/LARGE cogs be easy to aim at
     * without enlarging their wrench/configuration selector.
     */
    public static @Nullable TransmissionBoxCorner resolveConfiguredCogPlacement(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        TransmissionBoxCorner corner = cornerFromHit(local, face);
        TransmissionBoxCogMode mode = box.cornerMode(corner);
        if (mode == TransmissionBoxCogMode.EMPTY) {
            return null;
        }
        return isConfiguredCogProjection(local, face, corner, mode) ? corner : null;
    }

    public static double cornerExtent(Direction.Axis axis, Direction.Axis structuralAxis) {
        return axis == structuralAxis ? CORNER_AXIS_DEPTH : CORNER_TANGENTIAL_EDGE;
    }

    private static TransmissionBoxCorner cornerFromHit(Vec3 local, Direction face) {
        int x = face.getAxis() == Direction.Axis.X
                ? axisSign(face)
                : coordinate(local, Direction.Axis.X) >= 0.5 ? 1 : -1;
        int y = face.getAxis() == Direction.Axis.Y
                ? axisSign(face)
                : coordinate(local, Direction.Axis.Y) >= 0.5 ? 1 : -1;
        int z = face.getAxis() == Direction.Axis.Z
                ? axisSign(face)
                : coordinate(local, Direction.Axis.Z) >= 0.5 ? 1 : -1;
        return TransmissionBoxCorner.fromSigns(x, y, z);
    }

    private static boolean isConfiguredCogProjection(
            Vec3 local,
            Direction face,
            TransmissionBoxCorner corner,
            TransmissionBoxCogMode mode) {
        double halfExtent = mode == TransmissionBoxCogMode.LARGE
                ? LARGE_COG_PLACEMENT_HALF_EXTENT
                : SMALL_COG_PLACEMENT_HALF_EXTENT;
        Direction.Axis first = firstTangent(face.getAxis());
        Direction.Axis second = secondTangent(face.getAxis());
        return inCenteredCogRange(coordinate(local, first), corner.cell(first), halfExtent)
                && inCenteredCogRange(coordinate(local, second), corner.cell(second), halfExtent);
    }

    private static boolean inCenteredCogRange(double value, int miniCell, double halfExtent) {
        double center = miniCell * 0.5 + 0.25;
        return Math.abs(value - center) <= halfExtent;
    }

    private static boolean isCornerCoordinate(
            double value,
            Direction.Axis axis,
            Direction.Axis structuralAxis) {
        double extent = cornerExtent(axis, structuralAxis);
        return value <= extent || value >= 1.0 - extent;
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
