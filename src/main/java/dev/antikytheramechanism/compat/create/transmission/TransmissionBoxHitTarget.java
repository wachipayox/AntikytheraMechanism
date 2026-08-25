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
     * General hit resolution used by cog placement helpers. A configured cog receives a centred
     * source region matching its visible footprint so Create's placement guide remains easy to aim.
     */
    public static TransmissionBoxHitTarget resolve(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box) {
        return resolveInternal(hit, box, false);
    }

    /**
     * Wrench/configuration hit resolution. Corner selectors deliberately keep one fixed volume
     * regardless of EMPTY/SMALL/LARGE so rendered cogs never grow the wrench hitbox and steal clicks
     * from neighboring face regions.
     */
    public static TransmissionBoxHitTarget resolveWrench(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box) {
        return resolveInternal(hit, box, true);
    }

    private static TransmissionBoxHitTarget resolveInternal(
            BlockHitResult hit,
            TransmissionBoxBlockEntity box,
            boolean fixedCornerSelector) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Direction.Axis structuralAxis = box.structuralAxis();

        Direction.Axis first = firstTangent(face.getAxis());
        Direction.Axis second = secondTangent(face.getAxis());
        double firstValue = coordinate(local, first);
        double secondValue = coordinate(local, second);

        TransmissionBoxCorner candidateCorner = cornerFromHit(local, face);
        TransmissionBoxCogMode configuredMode = box.cornerMode(candidateCorner);
        boolean cornerHit = fixedCornerSelector || configuredMode == TransmissionBoxCogMode.EMPTY
                ? isCornerCoordinate(firstValue, first, structuralAxis)
                        && isCornerCoordinate(secondValue, second, structuralAxis)
                : isConfiguredCogProjection(local, face, candidateCorner, configuredMode);
        if (cornerHit) {
            return new TransmissionBoxHitTarget(Kind.CORNER, face, candidateCorner);
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
