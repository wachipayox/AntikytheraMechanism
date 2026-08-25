package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps Create kinetic state transactional with mini-content transfers and physical host changes. */
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
        // Ownership has not necessarily committed yet; rebuild only at the post-tick boundary.
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
     * Cuts source relations invalidated once a Create/Sable physical-move journal has committed.
     * The cut deliberately includes stationary Antikythera Transmission Boxes at the moving Frame
     * boundary, because those macro KBEs can be the direct Create source (or dependent) of a mini KBE.
     */
    public static void disconnectContraptionCapture(
            ServerLevel level,
            Collection<UUID> movingAssemblyIds) {
        if (!ModList.get().isLoaded("create") || movingAssemblyIds.isEmpty()) {
            return;
        }

        List<MechanismAssembly> moving = resolveLive(level, movingAssemblyIds);
        List<MechanismAssembly> cohort = sameHostCohort(level, movingAssemblyIds);
        Set<TransmissionBoxBlockEntity> boundaryBoxes = boundaryTransmissionBoxes(level, moving);
        if (!cohort.isEmpty() || !boundaryBoxes.isEmpty()) {
            CreateContraptionKineticCut.disconnect(level, cohort, movingAssemblyIds, boundaryBoxes);
        }
    }

    /**
     * Re-advertises the current same-host topology after a physical Frame relocation has fully
     * committed. The actual refresh is deferred until all block/BE writes from the move are finished.
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

    /** Create-specific name retained for contraption placement call sites. */
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
            List<MechanismAssembly> live = resolveLive(level, refreshes);
            CreateContraptionKineticCut.refresh(level, live);
            for (TransmissionBoxBlockEntity box : boundaryTransmissionBoxes(level, live)) {
                if (!box.isRemoved()) {
                    box.attachKinetics();
                }
            }
        }
    }

    private static Set<TransmissionBoxBlockEntity> boundaryTransmissionBoxes(
            ServerLevel level,
            Collection<MechanismAssembly> assemblies) {
        Set<TransmissionBoxBlockEntity> boxes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MechanismAssembly assembly : assemblies) {
            for (BlockPos frame : assembly.frames()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos candidate = frame.offset(dx, dy, dz);
                            if (!level.hasChunkAt(candidate)
                                    || !MechanismAssemblyHost.sameResolvedHost(level, frame, candidate)) {
                                continue;
                            }
                            if (level.getBlockEntity(candidate) instanceof TransmissionBoxBlockEntity box) {
                                boxes.add(box);
                            }
                        }
                    }
                }
            }
        }
        return boxes;
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
