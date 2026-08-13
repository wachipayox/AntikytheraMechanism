package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Deferred lifecycle for content-backed Sable SubLevels.
 *
 * <p>A MechanismAssembly is the persistent logical object. Its Sable SubLevel is only a runtime
 * resource while the plot contains physical mini/service-shell blocks. Writes merely enqueue an
 * assembly id; retirement happens from the normal level-tick tail, never re-entrantly from inside a
 * LevelChunk#setBlockState call.</p>
 */
public final class LazySubLevelLifecycle {
    private static final int LEGACY_SWEEP_INTERVAL = 20;
    private static final Map<ServerLevel, Set<UUID>> PENDING = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LAST_SWEEP = new WeakHashMap<>();

    private LazySubLevelLifecycle() {
    }

    public static void requestRetirementCheck(ServerLevel level, UUID assemblyId) {
        if (assemblyId == null) {
            return;
        }
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, ignored -> new HashSet<>()).add(assemblyId);
        }
    }

    public static void tick(ServerLevel level) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> candidates = drain(level);

        long gameTime = level.getGameTime();
        boolean sweepLegacy;
        synchronized (PENDING) {
            Long last = LAST_SWEEP.get(level);
            sweepLegacy = last == null || gameTime - last >= LEGACY_SWEEP_INTERVAL;
            if (sweepLegacy) {
                LAST_SWEEP.put(level, gameTime);
            }
        }
        if (sweepLegacy) {
            for (MechanismAssembly assembly : manager.assemblies()) {
                if (assembly.subLevelId() != null) {
                    candidates.add(assembly.id());
                }
            }
        }

        for (UUID assemblyId : candidates) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null || assembly.subLevelId() == null) {
                continue;
            }
            if (manager.isContentRecoveryLocked(assemblyId)
                    || manager.pendingPistonMove(assemblyId).isPresent()
                    || manager.pendingContraptionMove(assemblyId).isPresent()
                    || manager.pendingFrameEvacuation(assemblyId).isPresent()) {
                continue;
            }
            MechanismSubLevelService.retireIfEmpty(level, assembly);
        }
    }

    private static Set<UUID> drain(ServerLevel level) {
        synchronized (PENDING) {
            Set<UUID> pending = PENDING.remove(level);
            return pending == null ? new HashSet<>() : new HashSet<>(pending);
        }
    }
}
