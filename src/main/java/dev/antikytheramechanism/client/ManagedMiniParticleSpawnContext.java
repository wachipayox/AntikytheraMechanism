package dev.antikytheramechanism.client;

import java.util.function.Supplier;

/**
 * Marks the small window where Antikythera intentionally constructs TerrainParticles in managed
 * plot coordinates so Sable can perform its normal one-time kick-out before the particles are
 * detached permanently into the parent world.
 */
public final class ManagedMiniParticleSpawnContext {
    private static final ThreadLocal<Integer> DEFER_TO_SABLE_KICK_OUT_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ManagedMiniParticleSpawnContext() {
    }

    public static boolean isDeferringToSableKickOut() {
        return DEFER_TO_SABLE_KICK_OUT_DEPTH.get() > 0;
    }

    public static <T> T duringSableKickOut(Supplier<T> action) {
        int previous = DEFER_TO_SABLE_KICK_OUT_DEPTH.get();
        DEFER_TO_SABLE_KICK_OUT_DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                DEFER_TO_SABLE_KICK_OUT_DEPTH.remove();
            } else {
                DEFER_TO_SABLE_KICK_OUT_DEPTH.set(previous);
            }
        }
    }

    public static void duringSableKickOut(Runnable action) {
        duringSableKickOut(() -> {
            action.run();
            return null;
        });
    }
}
