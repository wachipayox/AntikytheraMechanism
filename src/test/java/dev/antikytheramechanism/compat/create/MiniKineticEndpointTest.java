package dev.antikytheramechanism.compat.create;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MiniKineticEndpointTest {
    @Test
    void valueIdentityIncludesAssemblyCoordinatesAndPortType() {
        UUID assemblyId = UUID.randomUUID();
        MiniKineticEndpoint first = new MiniKineticEndpoint(
                assemblyId, -4, 7, 12, KineticPortType.SMALL_COG);
        MiniKineticEndpoint copy = new MiniKineticEndpoint(
                assemblyId, -4, 7, 12, KineticPortType.SMALL_COG);

        assertNotSame(first, copy);
        assertEquals(first, copy);
        assertEquals(first.hashCode(), copy.hashCode());
    }

    @Test
    void requiredIdentityFieldsCannotBeNull() {
        UUID assemblyId = UUID.randomUUID();

        assertThrows(NullPointerException.class,
                () -> new MiniKineticEndpoint(null, 0, 0, 0, KineticPortType.SHAFT));
        assertThrows(NullPointerException.class,
                () -> new MiniKineticEndpoint(assemblyId, 0, 0, 0, null));
    }
}
