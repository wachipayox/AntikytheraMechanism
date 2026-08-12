package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRapierBoundsTest {
    @Test
    void recognizesSableEmptySentinelWithoutTreatingNormalBoundsAsEmpty() {
        assertTrue(ManagedRapierBounds.isEmptySentinel(BoundingBox3i.EMPTY));
        assertFalse(ManagedRapierBounds.isEmptySentinel(new BoundingBox3i(4, 5, 6, 4, 5, 6)));
    }

    @Test
    void convertsCenterOfMassToFiniteInclusiveNativeCell() {
        ManagedRapierBounds.NativeBounds bounds = ManagedRapierBounds.finitePointBounds(12.75, -3.1, 99.999);

        assertEquals(12, bounds.minX());
        assertEquals(-4, bounds.minY());
        assertEquals(99, bounds.minZ());
        assertEquals(bounds.minX(), bounds.maxX());
        assertEquals(bounds.minY(), bounds.maxY());
        assertEquals(bounds.minZ(), bounds.maxZ());
    }
}
