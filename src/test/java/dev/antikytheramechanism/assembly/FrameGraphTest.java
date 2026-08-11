package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameGraphTest {
    @Test
    void framesTouchingAnyOfTheSixFacesRemainOneComponent() {
        BlockPos center = new BlockPos(7, -3, 11);
        Set<BlockPos> frames = new HashSet<>();
        frames.add(center);
        for (Direction direction : Direction.values()) {
            frames.add(center.relative(direction));
        }

        List<Set<BlockPos>> components = FrameGraph.connectedComponents(frames);

        assertEquals(1, components.size());
        assertEquals(frames, components.getFirst());
    }

    @Test
    void edgeOrCornerContactDoesNotCreateContinuity() {
        BlockPos first = BlockPos.ZERO;
        BlockPos diagonal = new BlockPos(1, 1, 0);

        List<Set<BlockPos>> components = FrameGraph.connectedComponents(Set.of(first, diagonal));

        assertEquals(2, components.size());
        assertEquals(Set.of(Set.of(first), Set.of(diagonal)), new HashSet<>(components));
    }

    @Test
    void removingABridgeSplitsTheGraphIntoExpectedComponents() {
        BlockPos leftOuter = new BlockPos(-2, 0, 0);
        BlockPos leftInner = new BlockPos(-1, 0, 0);
        BlockPos bridge = BlockPos.ZERO;
        BlockPos rightInner = new BlockPos(1, 0, 0);
        BlockPos rightOuter = new BlockPos(2, 0, 0);
        Set<BlockPos> withBridge = Set.of(leftOuter, leftInner, bridge, rightInner, rightOuter);

        assertEquals(1, FrameGraph.connectedComponents(withBridge).size());

        Set<BlockPos> withoutBridge = new HashSet<>(withBridge);
        withoutBridge.remove(bridge);
        List<Set<BlockPos>> components = FrameGraph.connectedComponents(withoutBridge);

        assertEquals(2, components.size());
        assertEquals(
                Set.of(Set.of(leftOuter, leftInner), Set.of(rightInner, rightOuter)),
                new HashSet<>(components));
    }

    @Test
    void emptyGraphHasNoComponents() {
        assertEquals(List.of(), FrameGraph.connectedComponents(Set.of()));
    }
}
