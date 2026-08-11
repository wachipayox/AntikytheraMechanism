package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MechanismAssemblyTranslationTest {
    @Test
    void translatingOriginAndFramesKeepsEveryMiniPositionInvariant() {
        BlockPos origin = new BlockPos(-3, 8, 12);
        Set<BlockPos> frames = Set.of(origin, origin.east(), origin.above());
        MechanismAssembly assembly = new MechanismAssembly(UUID.randomUUID(), origin, frames);
        BlockPos delta = new BlockPos(0, 0, -1);

        for (BlockPos sourceFrame : frames) {
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 2; z++) {
                        BlockPos before = MiniCoordinateMapper.frameToMini(assembly, sourceFrame, x, y, z);
                        assembly.translate(delta);
                        BlockPos after = MiniCoordinateMapper.frameToMini(
                                assembly,
                                sourceFrame.offset(delta),
                                x,
                                y,
                                z);
                        assertEquals(before, after);
                        assembly.translate(new BlockPos(0, 0, 1));
                    }
                }
            }
        }

        assembly.translate(delta);
        assertEquals(origin.offset(delta), assembly.origin());
        assertEquals(
                frames.stream().map(frame -> frame.offset(delta)).collect(java.util.stream.Collectors.toSet()),
                assembly.frames());

        MechanismAssembly loaded = MechanismAssembly.load(assembly.save());
        assertEquals(assembly.origin(), loaded.origin());
        assertEquals(assembly.frames(), loaded.frames());
    }
}
