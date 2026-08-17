package dev.antikytheramechanism.compat.create;

/** Distinguishes controller destruction from a normal request to disassemble. */
public final class CreateForcedDisassemblyContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private CreateForcedDisassemblyContext() {
    }

    public static boolean isForced() {
        return DEPTH.get() > 0;
    }

    public static void runForced(Runnable action) {
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
}
