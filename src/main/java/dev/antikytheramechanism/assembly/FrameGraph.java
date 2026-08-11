package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Six-face connectivity for Mechanism Frames. Interior blocks never affect this graph. */
public final class FrameGraph {
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(pos -> pos.getZ())
            .thenComparingInt(pos -> pos.getX());

    private FrameGraph() {
    }

    public static List<Set<BlockPos>> connectedComponents(Collection<BlockPos> frames) {
        Set<BlockPos> remaining = new HashSet<>();
        frames.forEach(pos -> remaining.add(pos.immutable()));
        List<Set<BlockPos>> components = new ArrayList<>();

        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.stream().min(POSITION_ORDER).orElseThrow();
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
            remaining.remove(seed);
            frontier.add(seed);

            while (!frontier.isEmpty()) {
                BlockPos current = frontier.removeFirst();
                component.add(current);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.relative(direction);
                    if (remaining.remove(neighbor)) {
                        frontier.addLast(neighbor.immutable());
                    }
                }
            }
            components.add(component);
        }

        components.sort(Comparator
                .<Set<BlockPos>>comparingInt(Set::size)
                .reversed()
                .thenComparing(component -> component.stream().min(POSITION_ORDER).orElseThrow(), POSITION_ORDER));
        return components;
    }
}
