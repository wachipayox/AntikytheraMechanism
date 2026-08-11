package dev.antikytheramechanism.sublevel;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for one implementation-only block immediately outside a FrameMask. */
public record ServiceShellReservation(
        UUID assemblyId,
        BlockPos miniPosition,
        UUID ownerNonce,
        int portIndex,
        ResourceLocation expectedBlockId) {
    public ServiceShellReservation {
        Objects.requireNonNull(assemblyId, "assemblyId");
        miniPosition = miniPosition.immutable();
        Objects.requireNonNull(ownerNonce, "ownerNonce");
        if (portIndex < 0) {
            throw new IllegalArgumentException("portIndex must be non-negative");
        }
        Objects.requireNonNull(expectedBlockId, "expectedBlockId");
    }
}
