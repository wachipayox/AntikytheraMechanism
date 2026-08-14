package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanismFrameOrientationRendererTest {
    @Test
    void connectedLogicalCornerFacesHideOnlyAffectedMarkers() {
        FrameOrientation north = new FrameOrientation(Direction.UP, Direction.NORTH);
        for (int marker = 0; marker < 4; marker++) {
            assertTrue(FrameOrientationMarkerCulling.shouldRender(Set.of(), north, marker));
        }

        Set<Direction> northConnected = Set.of(Direction.NORTH);
        assertFalse(FrameOrientationMarkerCulling.shouldRender(northConnected, north, 0));
        assertFalse(FrameOrientationMarkerCulling.shouldRender(northConnected, north, 1));
        assertTrue(FrameOrientationMarkerCulling.shouldRender(northConnected, north, 2));
        assertTrue(FrameOrientationMarkerCulling.shouldRender(northConnected, north, 3));
    }

    @Test
    void markerCullingUsesPhysicalFaceAfterYawRotation() {
        FrameOrientation east = new FrameOrientation(Direction.UP, Direction.EAST);
        Set<Direction> connected = Set.of(east.toPhysical(Direction.NORTH));

        assertFalse(FrameOrientationMarkerCulling.shouldRender(connected, east, 0));
        assertFalse(FrameOrientationMarkerCulling.shouldRender(connected, east, 1));
        assertTrue(FrameOrientationMarkerCulling.shouldRender(connected, east, 2));
        assertTrue(FrameOrientationMarkerCulling.shouldRender(connected, east, 3));
    }
}
