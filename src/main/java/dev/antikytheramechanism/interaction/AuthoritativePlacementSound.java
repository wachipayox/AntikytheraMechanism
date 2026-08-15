package dev.antikytheramechanism.interaction;

import java.util.function.Supplier;

/**
 * Marks a placement that Antikythera intentionally executes only on the authoritative server.
 *
 * <p>Vanilla BlockItem placement excludes the placing player from the server sound broadcast because
 * the normal client-side placement path already played the same sound predictively. Antikythera's
 * routed mini/macro placements deliberately do not mutate the client world, so that exclusion would
 * make the placing player hear nothing. While this context is active the BlockItem sound hook keeps
 * vanilla's exact sound/volume/pitch calculation but includes the placing player in the broadcast.</p>
 */
public final class AuthoritativePlacementSound {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AuthoritativePlacementSound() {
    }

    public static <T> T includePlacingPlayer(Supplier<T> action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }

    public static boolean shouldIncludePlacingPlayer() {
        return DEPTH.get() > 0;
    }
}
