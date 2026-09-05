package dev.antikytheramechanism.sublevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small core-side event bus for effective block changes inside managed mini SubLevels.
 *
 * <p>Optional integrations register listeners without making the always-loaded Sable core reference
 * their classes. Ordinary listeners remain assembly-scoped; position-aware listeners can opt into the
 * logical mini position so per-Frame derived state does not need whole-assembly invalidation.</p>
 */
public final class MiniContentChangeBus {
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<PositionedListener> POSITIONED_LISTENERS = new CopyOnWriteArrayList<>();

    private MiniContentChangeBus() {
    }

    public static void register(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void registerPositioned(PositionedListener listener) {
        if (listener != null) {
            POSITIONED_LISTENERS.addIfAbsent(listener);
        }
    }

    public static void notifyChanged(ServerLevel level, UUID assemblyId) {
        notifyChanged(level, assemblyId, null);
    }

    public static void notifyChanged(ServerLevel level, UUID assemblyId, BlockPos logicalMiniPosition) {
        if (level == null || assemblyId == null) {
            return;
        }
        for (Listener listener : LISTENERS) {
            listener.onManagedMiniContentChanged(level, assemblyId);
        }
        if (logicalMiniPosition == null) {
            return;
        }
        BlockPos immutablePosition = logicalMiniPosition.immutable();
        for (PositionedListener listener : POSITIONED_LISTENERS) {
            listener.onManagedMiniContentChanged(level, assemblyId, immutablePosition);
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onManagedMiniContentChanged(ServerLevel level, UUID assemblyId);
    }

    @FunctionalInterface
    public interface PositionedListener {
        void onManagedMiniContentChanged(ServerLevel level, UUID assemblyId, BlockPos logicalMiniPosition);
    }
}
