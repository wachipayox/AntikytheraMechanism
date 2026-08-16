package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintHandle;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Prevents impossible contacts between a managed child and the physical space carrying it.
 *
 * <p>The child remains a distinct Minecraft SubLevel because it owns the real mini blocks. For a
 * ROOT-hosted assembly Sable still solves that child normally, with only child↔static-world contact
 * disabled. For a FOREIGN-hosted assembly {@link HostedMiniCollisionBridge} additionally mounts the
 * mini geometry directly on the foreign host rigid body and makes the child's own solver collider
 * inert. The free constraint remains as a defensive pair filter so a transient bounds refresh can
 * never reintroduce child↔host feedback before the proxy is reconciled.</p>
 */
public final class ManagedSubLevelCollisionPolicy {
    private static final int STATIC_WORLD_RUNTIME_ID = -1;
    private static final Map<ServerLevel, Map<UUID, Binding>> BINDINGS = new WeakHashMap<>();

    private ManagedSubLevelCollisionPolicy() {
    }

    /** Reconcile before every Rapier substep so forbidden host contacts never reach the solver. */
    public static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        PhysicsPipeline pipeline = event.getPhysicsSystem().getPipeline();
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);

        Map<UUID, Binding> levelBindings;
        synchronized (BINDINGS) {
            levelBindings = BINDINGS.computeIfAbsent(level, ignored -> new HashMap<>());
        }

        Set<UUID> liveAssemblies = new HashSet<>();
        for (MechanismAssembly assembly : manager.assemblies()) {
            liveAssemblies.add(assembly.id());
            reconcile(level, pipeline, assembly, levelBindings);
        }

        Iterator<Map.Entry<UUID, Binding>> iterator = levelBindings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Binding> entry = iterator.next();
            if (!liveAssemblies.contains(entry.getKey())) {
                remove(entry.getValue());
                iterator.remove();
            }
        }

        if (levelBindings.isEmpty()) {
            synchronized (BINDINGS) {
                BINDINGS.remove(level);
            }
        }

        // Pair filtering and mounted geometry are one pre-solver policy. Keeping the call here makes
        // ordering explicit: the host-contact guard is installed before the child collider is hidden.
        HostedMiniCollisionBridge.reconcile(level, pipeline, manager);
    }

    private static void reconcile(
            ServerLevel level,
            PhysicsPipeline pipeline,
            MechanismAssembly assembly,
            Map<UUID, Binding> levelBindings) {
        ServerSubLevel child = MechanismSubLevelService.get(level, assembly);
        if (child == null || child.isRemoved()) {
            remove(levelBindings.remove(assembly.id()));
            return;
        }

        MechanismAssemblyHost.Resolution host =
                MechanismAssemblyHost.resolve(level, assembly.origin());
        if (!host.allowed()) {
            remove(levelBindings.remove(assembly.id()));
            return;
        }

        ServerSubLevel hostBody = host.kind() == MechanismAssemblyHost.Kind.FOREIGN
                ? host.subLevel()
                : null;
        if (host.kind() == MechanismAssemblyHost.Kind.FOREIGN
                && (hostBody == null || hostBody.isRemoved())) {
            remove(levelBindings.remove(assembly.id()));
            return;
        }

        int childRuntimeId = child.getRuntimeId();
        int hostRuntimeId =
                hostBody == null ? STATIC_WORLD_RUNTIME_ID : hostBody.getRuntimeId();
        Binding existing = levelBindings.get(assembly.id());
        if (existing != null
                && existing.childRuntimeId() == childRuntimeId
                && existing.hostRuntimeId() == hostRuntimeId
                && existing.handle().isValid()) {
            return;
        }

        remove(existing);
        levelBindings.remove(assembly.id());

        BlockPos childPlotCenter = child.getPlot().getCenterBlock();
        Vector3d childAnchor = new Vector3d(
                childPlotCenter.getX() + 1.0,
                childPlotCenter.getY() + 1.0,
                childPlotCenter.getZ() + 1.0);

        Vector3d hostAnchor;
        if (hostBody != null) {
            hostAnchor = new Vector3d(
                    assembly.origin().getX() + 0.5,
                    assembly.origin().getY() + 0.5,
                    assembly.origin().getZ() + 0.5);
        } else {
            hostAnchor = assembly.poseTarget().anchor(new Vector3d());
        }

        FreeConstraintHandle handle;
        try {
            handle = pipeline.addConstraint(
                    child,
                    hostBody,
                    new FreeConstraintConfiguration(
                            childAnchor,
                            hostAnchor,
                            new Quaterniond()));
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not install host-contact suppression for managed assembly {}",
                    assembly.id(),
                    exception);
            return;
        }

        if (handle == null) {
            AntikytheraMechanism.LOGGER.error(
                    "Physics pipeline refused host-contact suppression for managed assembly {}",
                    assembly.id());
            return;
        }

        handle.setContactsEnabled(false);
        levelBindings.put(
                assembly.id(),
                new Binding(childRuntimeId, hostRuntimeId, handle));
    }

    private static void remove(Binding binding) {
        if (binding != null && binding.handle().isValid()) {
            binding.handle().remove();
        }
    }

    private record Binding(
            int childRuntimeId,
            int hostRuntimeId,
            FreeConstraintHandle handle) {
    }
}
