package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MiniContentChangeBus;
import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven runtime owner of derived mini-sail state for active bearing contraptions.
 *
 * <p>Every bearing tick performs only identity/pointer observation plus a cheap assembly shell-mode
 * comparison. Expensive Frame-cell discovery happens on first attachment, after an assembly-scoped
 * mini-content invalidation, or when a captured assembly changes NORMAL/GLASS/HIDDEN presentation.</p>
 */
public final class CreateMiniSailOverlayManager {
    private static final double EPSILON = 1.0E-9;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    private CreateMiniSailOverlayManager() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MiniContentChangeBus.register(CreateMiniSailOverlayManager::markDirty);
        }
    }

    public static void observe(MechanicalBearingBlockEntity bearing) {
        if (!(bearing.getLevel() instanceof ServerLevel level)) {
            return;
        }

        boolean relevant = bearing instanceof WindmillBearingBlockEntity
                || bearing instanceof MiniSailPropellerBridge;
        ControlledContraptionEntity moved = bearing.getMovedContraption();
        if (!relevant
                || !bearing.isRunning()
                || moved == null
                || !(moved.getContraption() instanceof BearingContraption contraption)) {
            forget(level, bearing);
            return;
        }

        LevelState state = STATES.computeIfAbsent(level, ignored -> new LevelState());
        Entry entry = state.entries.get(bearing);
        if (entry == null || entry.moved != moved || entry.contraption != contraption) {
            if (entry != null) {
                state.unindex(entry);
            }
            entry = new Entry(bearing, moved, contraption);
            state.entries.put(bearing, entry);
            refresh(level, state, entry);
            return;
        }

        if (!entry.dirty && shellModesChanged(level, entry)) {
            entry.dirty = true;
        }

        if (entry.dirty) {
            refresh(level, state, entry);
        } else if (entry.pendingInsufficientSailDisassembly) {
            tryPendingInsufficientSailDisassembly(level, state, entry);
        }
    }

    public static void forget(MechanicalBearingBlockEntity bearing) {
        if (bearing.getLevel() instanceof ServerLevel level) {
            forget(level, bearing);
        }
    }

    private static void forget(ServerLevel level, MechanicalBearingBlockEntity bearing) {
        LevelState state = STATES.get(level);
        if (state == null) {
            return;
        }
        Entry removed = state.entries.remove(bearing);
        if (removed != null) {
            state.unindex(removed);
        }
        if (state.entries.isEmpty()) {
            STATES.remove(level);
        }
    }

    private static void markDirty(ServerLevel level, UUID assemblyId) {
        LevelState state = STATES.get(level);
        if (state == null) {
            return;
        }
        Set<Entry> entries = state.byAssembly.get(assemblyId);
        if (entries == null) {
            return;
        }
        for (Entry entry : Set.copyOf(entries)) {
            entry.dirty = true;
        }
    }

    private static void refresh(ServerLevel level, LevelState state, Entry entry) {
        if (!safeToReadMiniContent(level, entry.snapshot.assemblyIds())) {
            entry.dirty = true;
            return;
        }

        DynamicMiniSailSnapshot.CaptureResult capture =
                DynamicMiniSailSnapshot.captureResult(level, entry.contraption);
        DynamicMiniSailSnapshot snapshot = capture.snapshot();
        if (!safeToReadMiniContent(level, snapshot.assemblyIds()) || !capture.complete()) {
            // Never replace an authoritative snapshot with a partial scan. Keeping dirty=true makes
            // the cheap bearing observation retry on the next tick until all captured Frames resolve.
            entry.dirty = true;
            return;
        }

        state.unindex(entry);
        entry.snapshot = snapshot;
        entry.shellModes = captureShellModes(level, snapshot.assemblyIds());
        entry.dirty = false;
        state.index(entry);
        if (entry.contraption instanceof DynamicMiniSailCarrier carrier) {
            carrier.antikytheramechanism$setMiniSails(snapshot);
        }

        boolean belowRequiredSails = false;

        if (entry.bearing instanceof WindmillBearingBlockEntity windmill) {
            double effectivePower = snapshot.effectiveSailPower(entry.contraption.getSailBlocks());
            int minimum = AllConfigs.server().kinetics.minimumWindmillSails.get();
            belowRequiredSails = effectivePower + EPSILON < minimum;
            if (!belowRequiredSails) {
                windmill.updateGeneratedRotation();
            }
        }

        if (entry.bearing instanceof MiniSailPropellerBridge propeller) {
            propeller.antikytheramechanism$refreshMiniSails(snapshot);
            belowRequiredSails = propeller.antikytheramechanism$getEffectiveSailPower() + EPSILON
                    < propeller.antikytheramechanism$getMinimumSailPower();
        }

        entry.pendingInsufficientSailDisassembly = belowRequiredSails;
        if (belowRequiredSails) {
            tryPendingInsufficientSailDisassembly(level, state, entry);
        }
    }

    private static boolean safeToReadMiniContent(ServerLevel level, Set<UUID> assemblyIds) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID assemblyId : assemblyIds) {
            // A Create pendingContraptionMove is intentionally NOT a veto: that journal remains alive
            // for the entire flight and the managed child is the authoritative mutable mini world.
            if (manager.pendingPistonMove(assemblyId).isPresent()
                    || manager.pendingFrameEvacuation(assemblyId).isPresent()
                    || manager.isContentRecoveryLocked(assemblyId)) {
                return false;
            }
        }
        return true;
    }

    private static Map<UUID, FrameShellMode> captureShellModes(ServerLevel level, Set<UUID> assemblyIds) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Map<UUID, FrameShellMode> result = new HashMap<>();
        for (UUID assemblyId : assemblyIds) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly != null) {
                result.put(assemblyId, assembly.shellMode());
            }
        }
        return Map.copyOf(result);
    }

    private static boolean shellModesChanged(ServerLevel level, Entry entry) {
        if (entry.shellModes.size() != entry.snapshot.assemblyIds().size()) {
            return true;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID assemblyId : entry.snapshot.assemblyIds()) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null || entry.shellModes.get(assemblyId) != assembly.shellMode()) {
                return true;
            }
        }
        return false;
    }

    private static void tryPendingInsufficientSailDisassembly(
            ServerLevel level,
            LevelState state,
            Entry entry) {
        if (!entry.pendingInsufficientSailDisassembly || !entry.bearing.isRunning()) {
            entry.pendingInsufficientSailDisassembly = false;
            return;
        }
        if (!safeToReadMiniContent(level, entry.snapshot.assemblyIds())) {
            return;
        }
        ControlledContraptionEntity moved = entry.bearing.getMovedContraption();
        if (moved == null || !CreateFrameDisassemblyPolicy.canVoluntarilyDisassemble(moved)) {
            return;
        }

        // Use Create's ordinary disassembly path for every sail-driven bearing. In particular, do not
        // call disassembleForMovement(), which deliberately queues reassembly and would create a loop.
        entry.bearing.disassemble();
        entry.pendingInsufficientSailDisassembly = false;
        state.entries.remove(entry.bearing);
        state.unindex(entry);
    }

    private static final class LevelState {
        private final Map<MechanicalBearingBlockEntity, Entry> entries = new IdentityHashMap<>();
        private final Map<UUID, Set<Entry>> byAssembly = new HashMap<>();

        private void index(Entry entry) {
            for (UUID assemblyId : entry.snapshot.assemblyIds()) {
                byAssembly.computeIfAbsent(
                                assemblyId,
                                ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                        .add(entry);
            }
        }

        private void unindex(Entry entry) {
            for (UUID assemblyId : entry.snapshot.assemblyIds()) {
                Set<Entry> entriesForAssembly = byAssembly.get(assemblyId);
                if (entriesForAssembly == null) {
                    continue;
                }
                entriesForAssembly.remove(entry);
                if (entriesForAssembly.isEmpty()) {
                    byAssembly.remove(assemblyId);
                }
            }
        }
    }

    private static final class Entry {
        private final MechanicalBearingBlockEntity bearing;
        private final ControlledContraptionEntity moved;
        private final BearingContraption contraption;
        private DynamicMiniSailSnapshot snapshot = DynamicMiniSailSnapshot.EMPTY;
        private Map<UUID, FrameShellMode> shellModes = Map.of();
        private boolean dirty = true;
        private boolean pendingInsufficientSailDisassembly;

        private Entry(
                MechanicalBearingBlockEntity bearing,
                ControlledContraptionEntity moved,
                BearingContraption contraption) {
            this.bearing = bearing;
            this.moved = moved;
            this.contraption = contraption;
        }
    }
}
