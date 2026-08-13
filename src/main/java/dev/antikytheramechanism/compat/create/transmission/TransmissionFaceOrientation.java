package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;
import java.util.Optional;

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

    /** Compatibility constructor for the persisted block-state face and its four-way roll. */
    public TransmissionFaceOrientation(Direction miniFace, int roll) {
        this(fromMiniFaceAndRoll(miniFace, roll));
    }

    private TransmissionFaceOrientation(TransmissionFaceOrientation orientation) {
        this(orientation.normal, orientation.horizontal, orientation.vertical);
    }

    public static TransmissionFaceOrientation of(Direction normal, Direction preferredUp) {
        Direction vertical = projectPreferred(normal, preferredUp);
        Direction horizontal = cross(vertical, normal);
        return new TransmissionFaceOrientation(normal, horizontal, vertical);
    }

    /** Direction from the frame towards the transmission box. */
    public Direction outward() {
        return normal;
    }

    /** Direction stored by the transmission-box {@code FACING} property. */
    public Direction miniFace() {
        return normal.getOpposite();
    }

    public Direction u() {
        return horizontal;
    }

    public Direction v() {
        return vertical;
    }

    public int roll() {
        Direction baseHorizontal = cross(projectPreferred(normal, Direction.UP), normal);
        Direction baseVertical = projectPreferred(normal, Direction.UP);
        Direction currentHorizontal = baseHorizontal;
        Direction currentVertical = baseVertical;
        for (int roll = 0; roll < 4; roll++) {
            if (currentHorizontal == horizontal && currentVertical == vertical) return roll;
            Direction previousHorizontal = currentHorizontal;
            currentHorizontal = currentVertical;
            currentVertical = previousHorizontal.getOpposite();
        }
        throw new IllegalStateException("Transmission face basis cannot be expressed as a roll");
    }

    public TransmissionFaceOrientation withRoll(int newRoll) {
        return new TransmissionFaceOrientation(miniFace(), newRoll);
    }

    public TransmissionFaceOrientation quarterTurn() {
        return withRoll(roll() + 1);
    }

    public TransmissionFaceOrientation rotate(Rotation rotation) {
        Direction rotatedFace = miniFace();
        Direction rotatedHorizontal = horizontal;
        for (int turn = 0; turn < rotation.ordinal(); turn++) {
            rotatedFace = rotatedFace.getClockWise(Direction.Axis.Y);
            rotatedHorizontal = rotatedHorizontal.getClockWise(Direction.Axis.Y);
        }
        return fromMiniFaceAndU(rotatedFace, rotatedHorizontal).orElseThrow();
    }

    public TransmissionFaceOrientation mirror(Mirror mirror) {
        return fromMiniFaceAndU(mirror.mirror(miniFace()), mirror.mirror(horizontal)).orElseThrow();
    }

    public TransmissionFaceOrientation rotateAround(Direction.Axis axis, int quarterTurns) {
        Direction rotatedFace = miniFace();
        Direction rotatedHorizontal = horizontal;
        for (int turn = 0; turn < Math.floorMod(quarterTurns, 4); turn++) {
            rotatedFace = rotatedFace.getClockWise(axis);
            rotatedHorizontal = rotatedHorizontal.getClockWise(axis);
        }
        return fromMiniFaceAndU(rotatedFace, rotatedHorizontal).orElseThrow();
    }

    public static Optional<TransmissionFaceOrientation> fromMiniFaceAndU(
            Direction miniFace,
            Direction expectedHorizontal) {
        if (miniFace.getAxis() == expectedHorizontal.getAxis()) return Optional.empty();
        for (int roll = 0; roll < 4; roll++) {
            TransmissionFaceOrientation candidate = new TransmissionFaceOrientation(miniFace, roll);
            if (candidate.horizontal == expectedHorizontal) return Optional.of(candidate);
        }
        return Optional.empty();
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
        int x = left.getStepY() * right.getStepZ() - left.getStepZ() * right.getStepY();
        int y = left.getStepZ() * right.getStepX() - left.getStepX() * right.getStepZ();
        int z = left.getStepX() * right.getStepY() - left.getStepY() * right.getStepX();
        Direction direction = Direction.fromDelta(x, y, z);
        if (direction == null) {
            throw new IllegalArgumentException("Transmission basis directions are parallel");
        }
        return direction;
    }

    private static TransmissionFaceOrientation fromMiniFaceAndRoll(Direction miniFace, int roll) {
        Objects.requireNonNull(miniFace, "miniFace");
        Direction normal = miniFace.getOpposite();
        Direction vertical = projectPreferred(normal, Direction.UP);
        Direction horizontal = cross(vertical, normal);
        for (int turn = 0; turn < Math.floorMod(roll, 4); turn++) {
            Direction previousHorizontal = horizontal;
            horizontal = vertical;
            vertical = previousHorizontal.getOpposite();
        }
        return new TransmissionFaceOrientation(normal, horizontal, vertical);
    }
}
