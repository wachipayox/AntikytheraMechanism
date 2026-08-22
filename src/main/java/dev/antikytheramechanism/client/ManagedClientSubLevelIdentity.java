package dev.antikytheramechanism.client;

import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stable client-side identity for Antikythera-managed Sable SubLevels.
 *
 * <p>The Sable tracking packet carries the persistent SubLevel UUID before the client-side SubLevel
 * has finished all bootstrap work. Relying only on the display name is therefore unnecessarily
 * fragile around creation, empty bounds and teardown. UUIDs are unique for the lifetime of the
 * SubLevel, so retaining them for the client process is safe even after a world unload.</p>
 */
public final class ManagedClientSubLevelIdentity {
    private static final Set<UUID> MANAGED_IDS = ConcurrentHashMap.newKeySet();
    private static final String MANAGED_NAME_PREFIX = "antikythera-";

    private ManagedClientSubLevelIdentity() {
    }

    public static void register(UUID subLevelId) {
        MANAGED_IDS.add(subLevelId);
    }

    public static boolean isManaged(@Nullable SubLevel subLevel) {
        if (subLevel == null) {
            return false;
        }

        UUID id = subLevel.getUniqueId();
        if (id != null && MANAGED_IDS.contains(id)) {
            return true;
        }

        String name = subLevel.getName();
        return name != null && name.startsWith(MANAGED_NAME_PREFIX);
    }

    /** Returns the owning Mechanism assembly encoded in a managed child's stable name. */
    public static @Nullable UUID assemblyId(@Nullable SubLevel subLevel) {
        if (subLevel == null) {
            return null;
        }
        String name = subLevel.getName();
        if (name == null || !name.startsWith(MANAGED_NAME_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(MANAGED_NAME_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
