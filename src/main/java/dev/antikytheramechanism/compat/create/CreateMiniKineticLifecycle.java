package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps Create kinetic state transactional with mini-content transfers without rebuilding a graph
 * against half-committed Frame ownership.
 */
public final class CreateMiniKineticLifecycle implements AssemblyLifecycleListener {
    private static final CreateMiniKineticLifecycle INSTANCE = new CreateMiniKineticLifecycle();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<ServerLevel, Set<UUID>> PENDING_REBUILDS = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<UUID>> PENDING_REFRESHES = new WeakHashMap<>();

    private CreateMiniKineticLifecycle() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        AssemblyLifecycleEvents.register(AntikytheraMechanism.id("create_mini_kinetics"), INSTANCE);
        NeoForge.EVENT_BUS.addListener(CreateMiniKineticLifecycle::onLevelTick);
    }

    @Override
    public boolean beforeAssemblyTransfer(AssemblyTransferContext context) {
        CreateMiniKineticTopology.quiesceAssemblies(
                context.level(), List.of(context.source(), context.target()));
        mark(context.level(), context.source().id(), context.target().id());
        return true;
    }

    @Override
    public boolean afterAssemblyTransfer(AssemblyTransferContext context) {
        // AssemblyContentTransferService completes before the manager commits source.removeFrames(),
        // merge removal or split ownership. Attaching here is intentionally forbidden.
        mark(context.level(), context.source().id(), context.target().id());
        return true;
    }

    @Override
    public boolean onAssemblyTransferRollback(
            AssemblyTransferContext context,
            boolean contentRestored) {
        if (contentRestored) {
            mark(context.level(), context.source().id(), context.target().id());
        }
        return contentRestored;
    }

    @Override
    public boolean beforeFrameEvacuation(FrameEvacuationContext context) {
        // Clearing a concrete mini KBE already invokes Create's ordinary removal lifecycle. Do not
        // quiesce unrelated survivors before the Frame graph has actually changed.
        mark(context.level(), context.assembly().id());
        return true;
    }

    @Override
    public boolean afterFrameEvacuation(FrameEvacuationContext context) {
        mark(context.level(), context.assembly().id());
        return true;
    }

    @Override
    public boolean onFrameEvacuationRollback(
            FrameEvacuationContext context,
            boolean contentRestored) {
        if (contentRestored) {
            mark(context.level(), context.assembly().id());
        }
        return contentRestored;
    }

    /**
     * Cuts only the live source relations that cross the moving/static partition after a Create
     * capture journal commits.
     *
     * <p>The pending move already hides moving assemblies from future virtual-neighbour discovery.
     * Asking Create to repair the dependent subtree of each now-forbidden source edge is therefore
     * sufficient to remove stale power. Rebuilding the complete same-host cohort here is incorrect:
     * {@code detachKinetics()} itself runs Create's destructive missing-source propagation, so doing
     * it node-by-node can interleave partial repairs and corrupt a larger gear train.</p>
     */
    public static void disconnectContraptionCapture(
            ServerLevel level,
            Collection<UUID> movingAssemblyIds) {
        List<MechanismAssembly> cohort = sameHostCohort(level, movingAssemblyIds);
        if (cohort.isEmpty()) {
            return;
        }
        CreateContraptionKineticCut.disconnect(level, cohort, movingAssemblyIds);
    }

    /**
     * Re-advertises the current same-host topology after any physical Frame relocation has fully
     * committed. Existing healthy source trees are left intact; newly legal virtual diagonals are
     * discovered by Create's ordinary attach/propagation rules.
     *
     * <p>This entry point is intentionally safe from core movement code: when Create is absent it is
     * a strict no-op, so piston/Sable relocation paths can call it without linking Create classes or
     * retaining pointless pending refresh state.</p>
     */
    public static void scheduleAfterPhysicalRelocation(
            ServerLevel level,
            Collection<UUID> placedAssemblyIds) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        List<MechanismAssembly> cohort = sameHostCohort(level, placedAssemblyIds);
        if (cohort.isEmpty()) {
            markRefresh(level, placedAssemblyIds.toArray(UUID[]::new));
            return;
        }
        markRefresh(level, cohort.stream().map(MechanismAssembly::id).toArray(UUID[]::new));
    }

    /** Create-specific name retained for the contraption placement call sites. */
    public static void scheduleAfterContraptionPlacement(
            ServerLevel level,
            Collection<UUID> placedAssemblyIds) {
        scheduleAfterPhysicalRelocation(level, placedAssemblyIds);
    }

    private static List<MechanismAssembly> sameHostCohort(
            ServerLevel level,
            Collection<UUID> seedIds) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<MechanismAssembly> seeds = seedIds.stream()
                .distinct()
                .map(id -> manager.getAssembly(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (seeds.isEmpty()) {
            return List.of();
        }
        return manager.assemblies().stream()
                .filter(candidate -> seeds.stream().anyMatch(seed ->
                        MechanismAssemblyHost.sameResolvedHost(
                                level, seed.origin(), candidate.origin())))
                .toList();
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Set<UUID> rebuilds = drain(PENDING_REBUILDS, level);
        if (rebuilds != null && !rebuilds.isEmpty()) {
            CreateMiniKineticTopology.rebuildAssemblies(level, resolveLive(level, rebuilds));
        }

        Set<UUID> refreshes = drain(PENDING_REFRESHES, level);
        if (refreshes != null && !refreshes.isEmpty()) {
            CreateContraptionKineticCut.refresh(level, resolveLive(level, refreshes));
        }
    }

    private static List<MechanismAssembly> resolveLive(ServerLevel level, Collection<UUID> ids) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        return ids.stream()
                .map(id -> manager.getAssembly(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Set<UUID> drain(Map<ServerLevel, Set<UUID>> queue, ServerLevel level) {
        synchronized (queue) {
            return queue.remove(level);
        }
    }

    private static void mark(ServerLevel level, UUID... assemblyIds) {
        mark(PENDING_REBUILDS, level, assemblyIds);
    }

    private static void markRefresh(ServerLevel level, UUID... assemblyIds) {
        mark(PENDING_REFRESHES, level, assemblyIds);
    }

    private static void mark(Map<ServerLevel, Set<UUID>> queue, ServerLevel level, UUID... assemblyIds) {
        synchronized (queue) {
            Set<UUID> pending = queue.computeIfAbsent(level, ignored -> new HashSet<>());
            java.util.Collections.addAll(pending, assemblyIds);
        }
    }
}
