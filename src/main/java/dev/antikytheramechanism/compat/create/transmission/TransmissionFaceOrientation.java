package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3i;

import java.util.Objects;

/** Right-handed face basis shared by transmission-box rendering and mini-port layout. */
public record TransmissionFaceOrientation(
        Direction normal,
        Direction horizontal,
        Direction vertical) {

    public TransmissionFaceOrientation {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(horizontal, "horizontal");
        Objects.requireNonNull(vertical, "vertical");
        if (normal.getAxis() == horizontal.getAxis()
                || normal.getAxis() == vertical.getAxis()
                || horizontal.getAxis() == vertical.getAxis()) {
            throw new IllegalArgumentException("Transmission face basis axes must be perpendicular");
        }
        if (cross(horizontal, vertical) != normal) {
            throw new IllegalArgumentException("Transmission face basis must be right-handed");
        }
    }

    public static TransmissionFaceOrientation of(Direction normal, Direction preferredUp) {
        Direction vertical = projectPreferred(normal, preferredUp);
        Direction horizontal = cross(vertical, normal);
        return new TransmissionFaceOrientation(normal, horizontal, vertical);
    }

    /** Converts a physical transmission-box basis into the immutable logical mini-world basis. */
    public TransmissionFaceOrientation toLogical(FrameOrientation frameOrientation) {
        return new TransmissionFaceOrientation(
                frameOrientation.toLogical(normal),
                frameOrientation.toLogical(horizontal),
                frameOrientation.toLogical(vertical));
    }

    public BlockPos offset(BlockPos origin, int horizontalStep, int verticalStep, int normalStep) {
        return origin.offset(
                horizontal.getStepX() * horizontalStep
                        + vertical.getStepX() * verticalStep
                        + normal.getStepX() * normalStep,
                horizontal.getStepY() * horizontalStep
                        + vertical.getStepY() * verticalStep
                        + normal.getStepY() * normalStep,
                horizontal.getStepZ() * horizontalStep
                        + vertical.getStepZ() * verticalStep
                        + normal.getStepZ() * normalStep);
    }

    private static Direction projectPreferred(Direction normal, Direction preferred) {
        if (preferred.getAxis() != normal.getAxis()) {
            return preferred;
        }
        return switch (normal.getAxis()) {
            case Y -> Direction.NORTH;
            case X, Z -> Direction.UP;
        };
    }

    private static Direction cross(Direction left, Direction right) {
        Vector3i a = new Vector3i(left.getStepX(), left.getStepY(), left.getStepZ());
        Vector3i b = new Vector3i(right.getStepX(), right.getStepY(), right.getStepZ());
        Vector3i cross = a.cross(b, new Vector3i());
        Direction direction = Direction.fromDelta(cross.x, cross.y, cross.z);
        if (direction == null) {
            throw new IllegalArgumentException("Transmission basis directions are parallel");
        }
        return direction;
    }
}
