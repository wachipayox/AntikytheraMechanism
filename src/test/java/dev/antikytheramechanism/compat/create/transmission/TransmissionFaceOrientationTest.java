package dev.antikytheramechanism.compat.create.transmission;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionFaceOrientationTest {
    @Test
    void enumeratesAllTwentyFourCubeFaceOrientations() {
        Set<String> bases = new HashSet<>();
        for (Direction face : Direction.values()) {
            for (int roll = 0; roll < 4; roll++) {
                TransmissionFaceOrientation orientation = new TransmissionFaceOrientation(face, roll);
                assertNotEquals(face.getAxis(), orientation.u().getAxis());
                assertNotEquals(face.getAxis(), orientation.v().getAxis());
                assertNotEquals(orientation.u().getAxis(), orientation.v().getAxis());
                bases.add(face + ":" + orientation.u());
                assertEquals(
                        orientation,
                        TransmissionFaceOrientation.fromMiniFaceAndU(face, orientation.u()).orElseThrow());
            }
        }
        assertEquals(24, bases.size());
    }

    @Test
    void rotationsAndMirrorsRemainInvertibleOrientations() {
        TransmissionFaceOrientation original = new TransmissionFaceOrientation(Direction.NORTH, 1);
        TransmissionFaceOrientation rotated = original;
        for (int turn = 0; turn < 4; turn++) {
            rotated = rotated.rotate(Rotation.CLOCKWISE_90);
        }
        assertEquals(original, rotated);
        assertEquals(original, original.mirror(Mirror.LEFT_RIGHT).mirror(Mirror.LEFT_RIGHT));
        assertTrue(TransmissionFaceOrientation.fromMiniFaceAndU(
                original.miniFace(), original.miniFace()).isEmpty());
    }
}
