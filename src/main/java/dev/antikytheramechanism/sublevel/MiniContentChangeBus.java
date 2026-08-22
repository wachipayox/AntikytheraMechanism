package dev.antikytheramechanism.sublevel;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small core-side event bus for effective block changes inside managed mini SubLevels.
 *
 * <p>Optional integrations register listeners without making the always-loaded Sable core reference
 * their classes. Notifications are deliberately assembly-scoped so consumers can invalidate only the
 * derived state that actually depends on the changed Frame contents.</p>
 */
public final class MiniContentChangeBus {
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private MiniContentChangeBus() {
    }

    public static void register(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void notifyChanged(ServerLevel level, UUID assemblyId) {
        if (level == null || assemblyId == null) {
            return;
        }
        for (Listener listener : LISTENERS) {
            listener.onManagedMiniContentChanged(level, assemblyId);
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onManagedMiniContentChanged(ServerLevel level, UUID assemblyId);
    }
}
