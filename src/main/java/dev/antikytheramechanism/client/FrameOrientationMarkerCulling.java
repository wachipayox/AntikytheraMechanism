package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.Direction;

import java.util.Set;

/** Pure visibility policy for the four colored frame-orientation corner markers. */
final class FrameOrientationMarkerCulling {
    private static final Direction[][] CORNER_FACES = {
            {Direction.UP, Direction.NORTH, Direction.WEST},
            {Direction.UP, Direction.NORTH, Direction.EAST},
            {Direction.UP, Direction.SOUTH, Direction.EAST},
            {Direction.UP, Direction.SOUTH, Direction.WEST}
    };

    private FrameOrientationMarkerCulling() {}

    static boolean shouldRender(Set<Direction> connectedPhysicalFaces, FrameOrientation orientation, int marker) {
        if (marker < 0 || marker >= CORNER_FACES.length) {
            throw new IllegalArgumentException("Unknown orientation marker " + marker);
        }
        for (Direction logicalFace : CORNER_FACES[marker]) {
            if (connectedPhysicalFaces.contains(orientation.toPhysical(logicalFace))) {
                return false;
            }
        }
        return true;
    }
}
