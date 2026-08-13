package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MechanismAssemblyLazySubLevelTest {
    @Test
    void emptyLogicalAssemblyRoundTripsWithoutPhysicalSubLevelIdentity() {
        UUID assemblyId = UUID.randomUUID();
        BlockPos origin = new BlockPos(12, 64, -7);
        Set<BlockPos> frames = Set.of(origin, origin.east(), origin.above());
        MechanismAssembly assembly = new MechanismAssembly(assemblyId, origin, frames);

        CompoundTag saved = assembly.save();
        assertFalse(saved.hasUUID("sublevel_id"));

        MechanismAssembly restored = MechanismAssembly.load(saved);
        assertEquals(assemblyId, restored.id());
        assertEquals(origin, restored.origin());
        assertEquals(frames, restored.frames());
        assertEquals(assembly.poseTarget(), restored.poseTarget());
        assertNull(restored.subLevelId());
    }
}
