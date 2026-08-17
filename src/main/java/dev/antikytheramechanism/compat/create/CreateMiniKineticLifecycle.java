package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import net.minecraft.server.level.ServerLevel;
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
     * Rebuilds the complete same-host kinetic cohort immediately after a Create capture journal is
     * committed but before Create removes the outer Frames.
     *
     * <p>The pending move makes the captured assembly ineligible for virtual cross-assembly edges.
     * Detaching and reattaching the cohort at that exact point therefore removes already-materialized
     * virtual edges while preserving ordinary internal Create networks inside every assembly. Merely
     * blocking future neighbour discovery is insufficient: an existing KineticNetwork can otherwise
     * keep a stale source relation alive after the Frame has entered the contraption.</p>
     */
    public static void disconnectContraptionCapture(
            ServerLevel level,
            Collection<UUID> movingAssemblyIds) {
        List<MechanismAssembly> cohort = sameHostCohort(level, movingAssemblyIds);
        if (cohort.isEmpty()) {
            return;
        }
        CreateMiniKineticTopology.rebuildAssemblies(level, cohort);
        mark(level, cohort.stream().map(MechanismAssembly::id).toArray(UUID[]::new));
    }

    /** Re-arms the new same-host cohort after Create has committed the placed Frame topology. */
    public static void scheduleAfterContraptionPlacement(
            ServerLevel level,
            Collection<UUID> placedAssemblyIds) {
        List<MechanismAssembly> cohort = sameHostCohort(level, placedAssemblyIds);
        if (cohort.isEmpty()) {
            mark(level, placedAssemblyIds.toArray(UUID[]::new));
            return;
        }
        mark(level, cohort.stream().map(MechanismAssembly::id).toArray(UUID[]::new));
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
        Set<UUID> requested;
        synchronized (PENDING_REBUILDS) {
            requested = PENDING_REBUILDS.remove(level);
        }
        if (requested == null || requested.isEmpty()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<MechanismAssembly> live = requested.stream()
                .map(id -> manager.getAssembly(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        CreateMiniKineticTopology.rebuildAssemblies(level, live);
    }

    private static void mark(ServerLevel level, UUID... assemblyIds) {
        synchronized (PENDING_REBUILDS) {
            Set<UUID> pending = PENDING_REBUILDS.computeIfAbsent(level, ignored -> new HashSet<>());
            java.util.Collections.addAll(pending, assemblyIds);
        }
    }
}
