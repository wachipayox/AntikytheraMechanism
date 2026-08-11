package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameMaskTest {
    @Test
    void oneFrameOwnsExactlyItsTwoByTwoByTwoMiniCube() {
        BlockPos origin = new BlockPos(100, 64, -100);
        FrameMask mask = new FrameMask(origin, Set.of(origin));
        Set<BlockPos> owned = new HashSet<>();

        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -1; z <= 2; z++) {
                    BlockPos mini = new BlockPos(x, y, z);
                    if (mask.containsMini(mini)) {
                        owned.add(mini);
                    }
                }
            }
        }

        Set<BlockPos> expected = new HashSet<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    expected.add(new BlockPos(x, y, z));
                }
            }
        }

        assertEquals(expected, owned);
        assertEquals(8, owned.size());
    }

    @Test
    void negativeFrameOffsetOwnsBothNegativeMiniColumns() {
        BlockPos origin = new BlockPos(-12, 30, 8);
        BlockPos westFrame = origin.offset(-1, 0, 0);
        FrameMask mask = new FrameMask(origin, Set.of(origin, westFrame));

        for (int x = -2; x <= 1; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    assertTrue(mask.containsMini(new BlockPos(x, y, z)));
                }
            }
        }

        assertFalse(mask.containsMini(new BlockPos(-3, 0, 0)));
        assertFalse(mask.containsMini(new BlockPos(2, 0, 0)));
    }

    @Test
    void addingAndRemovingFramesImmediatelyChangesMiniOwnership() {
        BlockPos origin = BlockPos.ZERO;
        BlockPos eastFrame = origin.offset(1, 0, 0);
        FrameMask mask = new FrameMask(origin, Set.of(origin));

        assertFalse(mask.containsMini(new BlockPos(2, 0, 0)));

        mask.addFrame(eastFrame);
        assertTrue(mask.containsFrame(eastFrame));
        assertTrue(mask.containsMini(new BlockPos(2, 0, 0)));
        assertTrue(mask.containsMini(new BlockPos(3, 1, 1)));

        mask.removeFrame(eastFrame);
        assertFalse(mask.containsFrame(eastFrame));
        assertFalse(mask.containsMini(new BlockPos(2, 0, 0)));
    }
}
