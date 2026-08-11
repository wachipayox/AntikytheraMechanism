package dev.antikytheramechanism.sublevel;

/** Narrow thread-local marker used only while vanilla dispenser behavior is executing. */
public final class DispenserWriteContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private DispenserWriteContext() {
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int depth = DEPTH.get();
        if (depth <= 1) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth - 1);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
