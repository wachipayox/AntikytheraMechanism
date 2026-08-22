package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Dist-safe bridge for optional client feedback when a mini placement is rejected.
 *
 * <p>Common placement code may invoke this on either logical side. Dedicated servers retain the
 * no-op handler; the client registers the visual pulse during client renderer bootstrap.
 */
public final class FramePlacementFeedbackHooks {
    private static volatile BiConsumer<Level, BlockPos> rejectedPlacement = (level, position) -> {};

    private FramePlacementFeedbackHooks() {
    }

    public static void registerRejectedPlacementFeedback(BiConsumer<Level, BlockPos> handler) {
        rejectedPlacement = Objects.requireNonNull(handler, "handler");
    }

    public static void rejectedPlacement(Level level, BlockPos position) {
        if (level != null && position != null) {
            rejectedPlacement.accept(level, position);
        }
    }
}
