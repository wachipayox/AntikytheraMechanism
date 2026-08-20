package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.antikytheramechanism.mixin.Rapier3DInvoker;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Prototype that feeds Create Offroad's complete Wheel Mount contribution to Rapier as an actual
 * external force for exactly one Sable physics substep instead of as an instantaneous momentum impulse.
 *
 * <p>Offroad's queued vector is already J = F * dt. The prototype divides by that substep's dt,
 * converts the resulting local force and r x F torque into world coordinates, adds them immediately
 * before {@link PhysicsPipeline#physicsTick(double)}, and removes those exact world vectors immediately
 * afterwards. This preserves Sable's other persistent forces (notably buoyancy) and prevents wheel force
 * from accumulating into the next substep.</p>
 */
public final class OffroadNativeContinuousForcePrototype {
    private static volatile boolean enabled;
    private static volatile String runtimeFailure = "none";

    private static final Map<SubLevelPhysicsSystem, Map<ServerSubLevel, Accumulator>> PENDING =
            new WeakHashMap<>();

    private OffroadNativeContinuousForcePrototype() {
    }

    /**
     * Captures one complete, already-time-integrated Wheel Mount impulse for native force delivery.
     *
     * @return true when the caller must omit the normal Offroad ForceTotal contribution.
     */
    public static boolean captureWheelImpulse(
            ServerSubLevel subLevel,
            Vector3dc localPosition,
            Vector3dc localImpulse,
            double timeStep) {
        if (!enabled || !PatchedSableRapierNativeLoader.isLoaded() || subLevel == null || subLevel.isRemoved()) {
            return false;
        }
        if (!(timeStep > 0.0) || !Double.isFinite(timeStep)
                || !isFinite(localPosition) || !isFinite(localImpulse)) {
            return false;
        }
        if (localImpulse.lengthSquared() <= 1.0e-24) {
            return false;
        }

        final SubLevelPhysicsSystem physicsSystem;
        try {
            physicsSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (IllegalStateException ignored) {
            return false;
        }

        final Vector3dc centerOfMass = subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) {
            return false;
        }

        final Vector3d localForce = new Vector3d(localImpulse).div(timeStep);
        final Vector3d arm = new Vector3d(localPosition).sub(centerOfMass);
        final Vector3d localTorque = arm.cross(localForce, new Vector3d());
        final Vector3d localTorqueImpulse = new Vector3d(localTorque).mul(timeStep);

        final Vector3d worldForce = subLevel.logicalPose().transformNormal(new Vector3d(localForce));
        final Vector3d worldTorque = subLevel.logicalPose().transformNormal(new Vector3d(localTorque));

        if (!isFinite(worldForce) || !isFinite(worldTorque)) {
            return false;
        }

        synchronized (OffroadNativeContinuousForcePrototype.class) {
            Map<ServerSubLevel, Accumulator> bySubLevel =
                    PENDING.computeIfAbsent(physicsSystem, ignored -> new IdentityHashMap<>());
            Accumulator accumulator = bySubLevel.computeIfAbsent(subLevel, ignored -> new Accumulator());
            accumulator.localImpulse.add(localImpulse);
            accumulator.localTorqueImpulse.add(localTorqueImpulse);
            accumulator.worldForce.add(worldForce);
            accumulator.worldTorque.add(worldTorque);
        }
        return true;
    }

    /**
     * Executes one normal Sable/Rapier step while any captured wheel load exists as a true external
     * force. If no native wheel force is pending, this delegates to the existing microstep prototype so
     * both experiments can coexist and be switched independently at runtime.
     */
    public static void physicsTick(
            SubLevelPhysicsSystem physicsSystem,
            PhysicsPipeline pipeline,
            double timeStep) {
        final List<PendingForce> pending = drain(physicsSystem);
        if (pending.isEmpty()) {
            OffroadContinuousSuspensionPrototype.physicsTick(physicsSystem, pipeline, timeStep);
            return;
        }

        if (!enabled || !PatchedSableRapierNativeLoader.isLoaded()) {
            applyImpulseFallback(physicsSystem, pending);
            OffroadContinuousSuspensionPrototype.physicsTick(physicsSystem, pipeline, timeStep);
            return;
        }

        final long sceneHandle = Rapier3DInvoker.antikytheramechanism$getSceneHandle(pending.getFirst().subLevel().getLevel());
        int appliedCount = 0;
        try {
            for (PendingForce force : pending) {
                addNative(sceneHandle, force, 1.0, true);
                appliedCount++;
            }
        } catch (Throwable throwable) {
            for (int i = appliedCount - 1; i >= 0; i--) {
                try {
                    addNative(sceneHandle, pending.get(i), -1.0, false);
                } catch (Throwable ignored) {
                }
            }
            enabled = false;
            runtimeFailure = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
            applyImpulseFallback(physicsSystem, pending);
            OffroadContinuousSuspensionPrototype.physicsTick(physicsSystem, pipeline, timeStep);
            return;
        }

        try {
            // One ordinary Rapier step: no internal microstepping here.
            pipeline.physicsTick(timeStep);
        } finally {
            // Rapier external forces persist until reset. Remove exactly our world-space contribution
            // so buoyancy and any other persistent force accumulators remain untouched.
            for (int i = pending.size() - 1; i >= 0; i--) {
                addNative(sceneHandle, pending.get(i), -1.0, false);
            }
        }
    }

    private static List<PendingForce> drain(SubLevelPhysicsSystem physicsSystem) {
        synchronized (OffroadNativeContinuousForcePrototype.class) {
            Map<ServerSubLevel, Accumulator> bySubLevel = PENDING.remove(physicsSystem);
            if (bySubLevel == null || bySubLevel.isEmpty()) {
                return List.of();
            }
            List<PendingForce> result = new ArrayList<>(bySubLevel.size());
            for (Map.Entry<ServerSubLevel, Accumulator> entry : bySubLevel.entrySet()) {
                Accumulator accumulator = entry.getValue();
                result.add(new PendingForce(
                        entry.getKey(),
                        new Vector3d(accumulator.localImpulse),
                        new Vector3d(accumulator.localTorqueImpulse),
                        new Vector3d(accumulator.worldForce),
                        new Vector3d(accumulator.worldTorque)));
            }
            return result;
        }
    }

    private static void addNative(long sceneHandle, PendingForce force, double scale, boolean wakeUp) {
        ServerSubLevel subLevel = force.subLevel();
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }
        Vector3dc f = force.worldForce();
        Vector3dc t = force.worldTorque();
        OffroadNativeForceBridge.addWorldForceAndTorque(
                sceneHandle,
                subLevel.getRuntimeId(),
                f.x() * scale,
                f.y() * scale,
                f.z() * scale,
                t.x() * scale,
                t.y() * scale,
                t.z() * scale,
                wakeUp);
    }

    private static void applyImpulseFallback(SubLevelPhysicsSystem physicsSystem, List<PendingForce> pending) {
        for (PendingForce force : pending) {
            ServerSubLevel subLevel = force.subLevel();
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            handle.applyLinearAndAngularImpulse(force.localImpulse(), force.localTorqueImpulse(), true);
        }
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad_native")
                                .then(Commands.literal("enabled")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    enabled = BoolArgumentType.getBool(context, "enabled");
                                                    if (enabled && !PatchedSableRapierNativeLoader.isLoaded()) {
                                                        enabled = false;
                                                        context.getSource().sendFailure(Component.literal(
                                                                "Offroad native force unavailable: "
                                                                        + PatchedSableRapierNativeLoader.failure()));
                                                        return 0;
                                                    }
                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("Offroad native continuous force: "
                                                                    + (enabled ? "ENABLED" : "DISABLED")),
                                                            false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("status")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Offroad native continuous force: enabled="
                                                            + enabled
                                                            + "; patched_native_loaded=" + PatchedSableRapierNativeLoader.isLoaded()
                                                            + "; native=" + PatchedSableRapierNativeLoader.loadedName()
                                                            + "; load_failure=" + PatchedSableRapierNativeLoader.failure()
                                                            + "; runtime_failure=" + runtimeFailure),
                                                    false);
                                            return 1;
                                        }))
                                .then(Commands.literal("reset")
                                        .executes(context -> {
                                            enabled = false;
                                            runtimeFailure = "none";
                                            synchronized (OffroadNativeContinuousForcePrototype.class) {
                                                PENDING.clear();
                                            }
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Offroad native continuous force reset: enabled=false"),
                                                    false);
                                            return 1;
                                        })))
        );
    }

    private static final class Accumulator {
        private final Vector3d localImpulse = new Vector3d();
        private final Vector3d localTorqueImpulse = new Vector3d();
        private final Vector3d worldForce = new Vector3d();
        private final Vector3d worldTorque = new Vector3d();
    }

    private record PendingForce(
            ServerSubLevel subLevel,
            Vector3d localImpulse,
            Vector3d localTorqueImpulse,
            Vector3d worldForce,
            Vector3d worldTorque) {
    }
}
