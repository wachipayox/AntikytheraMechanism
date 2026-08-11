package dev.antikytheramechanism.compat.create.transmission;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;
import java.util.Optional;

/** A complete cube-face orientation: one normal and one of four rolls around it. */
public record TransmissionFaceOrientation(Direction miniFace, int roll) {
    public TransmissionFaceOrientation {
        Objects.requireNonNull(miniFace, "miniFace");
        roll = Math.floorMod(roll, 4);
    }

    /** Direction from the adjacent frame towards the transmission box. */
    public Direction outward() {
        return miniFace.getOpposite();
    }

    /** First positive quadrant direction on the mini face. */
    public Direction u() {
        Direction u = baseU(outward());
        Direction v = baseV(outward());
        for (int turn = 0; turn < roll; turn++) {
            Direction previousU = u;
            u = v;
            v = previousU.getOpposite();
        }
        return u;
    }

    /** Second positive quadrant direction on the mini face. */
    public Direction v() {
        Direction u = baseU(outward());
        Direction v = baseV(outward());
        for (int turn = 0; turn < roll; turn++) {
            Direction previousU = u;
            u = v;
            v = previousU.getOpposite();
        }
        return v;
    }

    public TransmissionFaceOrientation withRoll(int newRoll) {
        return new TransmissionFaceOrientation(miniFace, newRoll);
    }

    public TransmissionFaceOrientation quarterTurn() {
        return withRoll(roll + 1);
    }

    public TransmissionFaceOrientation rotate(Rotation rotation) {
        Direction rotatedFace = miniFace;
        Direction rotatedU = u();
        for (int turn = 0; turn < rotation.ordinal(); turn++) {
            rotatedFace = rotatedFace.getClockWise(Direction.Axis.Y);
            rotatedU = rotatedU.getClockWise(Direction.Axis.Y);
        }
        return fromMiniFaceAndU(rotatedFace, rotatedU).orElseThrow();
    }

    public TransmissionFaceOrientation mirror(Mirror mirror) {
        Direction mirroredFace = mirror.mirror(miniFace);
        Direction mirroredU = mirror.mirror(u());
        return fromMiniFaceAndU(mirroredFace, mirroredU).orElseThrow();
    }

    public TransmissionFaceOrientation rotateAround(Direction.Axis axis, int quarterTurns) {
        Direction rotatedFace = miniFace;
        Direction rotatedU = u();
        for (int turn = 0; turn < Math.floorMod(quarterTurns, 4); turn++) {
            rotatedFace = rotatedFace.getClockWise(axis);
            rotatedU = rotatedU.getClockWise(axis);
        }
        return fromMiniFaceAndU(rotatedFace, rotatedU).orElseThrow();
    }

    public static Optional<TransmissionFaceOrientation> fromMiniFaceAndU(
            Direction miniFace,
            Direction expectedU) {
        if (miniFace.getAxis() == expectedU.getAxis()) {
            return Optional.empty();
        }
        for (int roll = 0; roll < 4; roll++) {
            TransmissionFaceOrientation candidate = new TransmissionFaceOrientation(miniFace, roll);
            if (candidate.u() == expectedU) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Direction baseV(Direction outward) {
        return outward.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    private static Direction baseU(Direction outward) {
        return cross(baseV(outward), outward);
    }

    private static Direction cross(Direction first, Direction second) {
        int x = first.getStepY() * second.getStepZ() - first.getStepZ() * second.getStepY();
        int y = first.getStepZ() * second.getStepX() - first.getStepX() * second.getStepZ();
        int z = first.getStepX() * second.getStepY() - first.getStepY() * second.getStepX();
        return Direction.getNearest(x, y, z);
    }
}
