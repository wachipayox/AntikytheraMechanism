package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.compat.create.KineticPortType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionPortLayoutTest {
    private static final BlockPos FRAME_BASE = new BlockPos(-4, 6, 10);
    private static final TransmissionFaceOrientation NORTH =
            new TransmissionFaceOrientation(Direction.NORTH, 0);

    @Test
    void shaftBoxUsesFourOutsideServiceCellsAndHonoursCovers() {
        List<TransmissionPortLayout.PortPlacement> all = TransmissionPortLayout.create(
                TransmissionBoxKind.FOUR_SHAFTS, NORTH, false, 0, FRAME_BASE);
        assertEquals(4, all.size());
        assertEquals(4, all.stream().map(TransmissionPortLayout.PortPlacement::serviceLocalPosition)
                .collect(java.util.stream.Collectors.toSet()).size());
        for (TransmissionPortLayout.PortPlacement port : all) {
            assertEquals(KineticPortType.SHAFT, port.servicePortType());
            assertEquals(KineticPortType.SHAFT, port.targetPortType());
            assertEquals(1.0D, port.effectiveFactor());
            assertFalse(insideFrame(port.serviceLocalPosition()));
            assertTrue(insideFrame(port.targetLocalPosition()));
        }

        List<TransmissionPortLayout.PortPlacement> covered = TransmissionPortLayout.create(
                TransmissionBoxKind.FOUR_SHAFTS, NORTH, false, (1 << 1) | (1 << 3), FRAME_BASE);
        assertEquals(Set.of(0, 2), covered.stream()
                .map(TransmissionPortLayout.PortPlacement::portIndex)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void diagonalSelectionsAreComplementaryAndUseNativeCreateFactors() {
        assertDiagonalKind(TransmissionBoxKind.TWO_LARGE_COGS, -2.0D);
        assertDiagonalKind(TransmissionBoxKind.TWO_SMALL_COGS, -0.5D);
    }

    private static void assertDiagonalKind(TransmissionBoxKind kind, double factor) {
        List<TransmissionPortLayout.PortPlacement> a =
                TransmissionPortLayout.create(kind, NORTH, false, 0, FRAME_BASE);
        List<TransmissionPortLayout.PortPlacement> b =
                TransmissionPortLayout.create(kind, NORTH, true, 0, FRAME_BASE);
        assertEquals(Set.of(0, 3), indices(a));
        assertEquals(Set.of(1, 2), indices(b));
        Set<Integer> union = new HashSet<>(indices(a));
        union.addAll(indices(b));
        assertEquals(Set.of(0, 1, 2, 3), union);

        for (TransmissionPortLayout.PortPlacement port :
                java.util.stream.Stream.concat(a.stream(), b.stream()).toList()) {
            assertEquals(factor, port.effectiveFactor());
            BlockPos difference = port.targetLocalPosition().subtract(port.serviceLocalPosition());
            assertEquals(2, difference.distManhattan(BlockPos.ZERO));
            assertEquals(0, port.rotationAxis().choose(
                    difference.getX(), difference.getY(), difference.getZ()));
        }
    }

    private static Set<Integer> indices(List<TransmissionPortLayout.PortPlacement> ports) {
        return ports.stream().map(TransmissionPortLayout.PortPlacement::portIndex)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean insideFrame(BlockPos position) {
        BlockPos local = position.subtract(FRAME_BASE);
        return local.getX() >= 0 && local.getX() <= 1
                && local.getY() >= 0 && local.getY() <= 1
                && local.getZ() >= 0 && local.getZ() <= 1;
    }
}
