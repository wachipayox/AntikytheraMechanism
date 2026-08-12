package dev.antikytheramechanism.client;

import java.util.function.Supplier;

/**
 * Small render-thread contexts used while constructing block-destruction TerrainParticles.
 *
 * <p>Managed mini debris is intentionally created in plot coordinates so Sable can perform its one
 * official local-to-world kick-out before Antikythera detaches it. Parent-world debris near an
 * Antikythera SubLevel needs the opposite treatment: it is already in world coordinates and should
 * be marked detached at construction time without any transform, so Sable never starts doing
 * SubLevel collision/light/tracking work for it.</p>
 */
public final class ManagedMiniParticleSpawnContext {
    private static final ThreadLocal<Integer> DEFER_TO_SABLE_KICK_OUT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> DETACH_PARENT_TERRAIN_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ManagedMiniParticleSpawnContext() {
    }

    public static boolean isDeferringToSableKickOut() {
        return DEFER_TO_SABLE_KICK_OUT_DEPTH.get() > 0;
    }

    public static boolean shouldDetachParentTerrainParticles() {
        return DETACH_PARENT_TERRAIN_DEPTH.get() > 0;
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

    public static <T> T duringParentTerrainDetach(Supplier<T> action) {
        int previous = DETACH_PARENT_TERRAIN_DEPTH.get();
        DETACH_PARENT_TERRAIN_DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                DETACH_PARENT_TERRAIN_DEPTH.remove();
            } else {
                DETACH_PARENT_TERRAIN_DEPTH.set(previous);
            }
        }
    }

    public static void duringParentTerrainDetach(Runnable action) {
        duringParentTerrainDetach(() -> {
            action.run();
            return null;
        });
    }
}
