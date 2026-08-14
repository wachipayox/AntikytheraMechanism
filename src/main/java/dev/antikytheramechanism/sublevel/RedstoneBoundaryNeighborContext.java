package dev.antikytheramechanism.sublevel;

import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

/** Carries the original vanilla neighbour-source block through one deferred boundary replay. */
final class RedstoneBoundaryNeighborContext {
    private static final ThreadLocal<Block> SOURCE_BLOCK = new ThreadLocal<>();

    private RedstoneBoundaryNeighborContext() {
    }

    static <T> T withSource(Block sourceBlock, Supplier<T> action) {
        Block previous = SOURCE_BLOCK.get();
        SOURCE_BLOCK.set(sourceBlock);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SOURCE_BLOCK.remove();
            } else {
                SOURCE_BLOCK.set(previous);
            }
        }
    }

    static void withSource(Block sourceBlock, Runnable action) {
        withSource(sourceBlock, () -> {
            action.run();
            return null;
        });
    }

    static Block sourceOr(Block fallback) {
        Block source = SOURCE_BLOCK.get();
        return source == null ? fallback : source;
    }
}
