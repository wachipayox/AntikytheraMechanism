package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.compat.create.KineticPortType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/** Pure conversion from an oriented 2x2 frame face to service-shell and target cells. */
public final class TransmissionPortLayout {
    private TransmissionPortLayout() {
    }

    /**
     * @param frameMiniBase local mini coordinate corresponding to cell (0,0,0) of the frame
     */
    public static List<PortPlacement> create(
            TransmissionBoxKind kind,
            TransmissionFaceOrientation orientation,
            boolean diagonalB,
            int coveredPortsMask,
            BlockPos frameMiniBase) {
        return kind.activeQuadrants(diagonalB).stream()
                .filter(quadrant -> !kind.supportsCovers() || (coveredPortsMask & 1 << quadrant) == 0)
                .map(quadrant -> placement(kind, orientation, frameMiniBase, quadrant))
                .toList();
    }

    public static PortPlacement placement(
            TransmissionBoxKind kind,
            TransmissionFaceOrientation orientation,
            BlockPos frameMiniBase,
            int quadrant) {
        if (quadrant < 0 || quadrant > 3) {
            throw new IllegalArgumentException("Quadrant must be in [0,3]: " + quadrant);
        }
        int uBit = quadrant & 1;
        int vBit = quadrant >>> 1;
        BlockPos inside = insideCell(frameMiniBase, orientation, uBit, vBit);
        BlockPos service = inside.relative(orientation.outward());
        BlockPos target = kind.usesDiagonalSelection()
                ? insideCell(frameMiniBase, orientation, uBit, 1 - vBit)
                : inside;
        Direction.Axis rotationAxis = kind.servicePortType() == KineticPortType.SHAFT
                ? orientation.outward().getAxis()
                : orientation.u().getAxis();
        return new PortPlacement(
                quadrant,
                service.immutable(),
                target.immutable(),
                kind.servicePortType(),
                kind.targetPortType(),
                rotationAxis,
                orientation.miniFace(),
                kind.effectiveFactor());
    }

    private static BlockPos insideCell(
            BlockPos frameMiniBase,
            TransmissionFaceOrientation orientation,
            int uBit,
            int vBit) {
        int[] coordinates = {0, 0, 0};
        setAxisBit(coordinates, orientation.outward(), 1);
        setAxisBit(coordinates, orientation.u(), uBit);
        setAxisBit(coordinates, orientation.v(), vBit);
        return frameMiniBase.offset(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static void setAxisBit(int[] coordinates, Direction positiveDirection, int bit) {
        int coordinate = positiveDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bit : 1 - bit;
        coordinates[positiveDirection.getAxis().ordinal()] = coordinate;
    }

    public record PortPlacement(
            int portIndex,
            BlockPos serviceLocalPosition,
            BlockPos targetLocalPosition,
            KineticPortType servicePortType,
            KineticPortType targetPortType,
            Direction.Axis rotationAxis,
            Direction shaftFacing,
            double effectiveFactor) {
    }
}
