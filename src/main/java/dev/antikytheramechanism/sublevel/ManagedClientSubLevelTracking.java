package dev.antikytheramechanism.sublevel;

/**
 * Marks the synchronous client-side tracking bootstrap of an Antikythera Sable SubLevel.
 *
 * <p>Sable applies the initial plot bounds and calls forceUpdateBounds before it assigns the
 * sub-level name from ClientboundStartTrackingSubLevelPacket. During that short window the usual
 * name-based managed-sublevel check cannot identify our empty plot, so client-only mixins use this
 * thread-local context instead.</p>
 */
public final class ManagedClientSubLevelTracking {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ManagedClientSubLevelTracking() {
    }

    public static void duringManagedTracking(Runnable action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
