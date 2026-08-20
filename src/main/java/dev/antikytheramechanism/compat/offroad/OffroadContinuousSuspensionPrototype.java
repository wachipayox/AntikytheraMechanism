package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * Experimental Create Offroad wheel-force integrator.
 *
 * <p>Offroad computes the complete Wheel Mount contribution (spring, damping, drive/braking and
 * lateral grip) as a momentum impulse for the current Sable substep. Sable's Rapier bridge applies
 * that contribution immediately through Rapier's {@code apply_impulse}/{@code apply_torque_impulse}
 * path. Runtime diagnosis showed that the phantom chassis creep disappears when the same integrated
 * wheel impulse is delivered as many small impulses with a Rapier solve between each impulse, even
 * when the Wheel Mount raycast/force calculation itself is still performed only twice per Minecraft
 * tick.</p>
 *
 * <p>This prototype therefore captures the complete final Wheel Mount impulse and its normal r x J
 * torque before Offroad submits it, then replays the same total linear/angular momentum over several
 * internal Rapier microsteps. It does not change gravity, total simulated time, wheel raycast cadence
 * or nominal integrated wheel momentum. It is a Java-side approximation of Rapier continuous forces;
 * Sable 2.0.3 uses {@code add_force_at_point} internally for buoyancy but does not expose that operation
 * through its public Java physics API.</p>
 */
public final class OffroadContinuousSuspensionPrototype {
    private static final int DEFAULT_MICROSTEPS = 15;

    private static volatile boolean enabled = true;
    private static volatile int microsteps = DEFAULT_MICROSTEPS;

    /** Pending complete wheel impulses collected during the current outer Sable substep. */
    private static final Map<SubLevelPhysicsSystem, Map<ServerSubLevel, Accumulator>> PENDING =
            new WeakHashMap<>();

    private OffroadContinuousSuspensionPrototype() {
    }

    /**
     * Captures one Wheel Mount's already-time-integrated complete impulse.
     *
     * @return true when the caller must omit this wheel contribution from Offroad's immediate
     *         ForceTotal because the prototype will deliver it during Rapier microsteps.
     */
    public static boolean captureWheelImpulse(
            ServerSubLevel subLevel,
            Vector3dc localPosition,
            Vector3dc localImpulse) {
        if (!enabled || microsteps <= 1 || subLevel == null || subLevel.isRemoved()) {
            return false;
        }
        if (!isFinite(localPosition) || !isFinite(localImpulse) || localImpulse.lengthSquared() <= 1.0e-24) {
            return false;
        }

        SubLevelPhysicsSystem physicsSystem;
        try {
            physicsSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (IllegalStateException ignored) {
            return false;
        }

        Vector3dc centerOfMass = subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) {
            return false;
        }

        Vector3d torqueImpulse = new Vector3d(localPosition)
                .sub(centerOfMass)
                .cross(localImpulse);

        synchronized (OffroadContinuousSuspensionPrototype.class) {
            Map<ServerSubLevel, Accumulator> bySubLevel =
                    PENDING.computeIfAbsent(physicsSystem, ignored -> new IdentityHashMap<>());
            Accumulator accumulator = bySubLevel.computeIfAbsent(subLevel, ignored -> new Accumulator());
            accumulator.linear.add(localImpulse);
            accumulator.angular.add(torqueImpulse);
        }
        return true;
    }

    /**
     * Replaces one normal {@link PhysicsPipeline#physicsTick(double)} invocation when wheel momentum
     * was captured during the current outer Sable substep.
     */
    public static void physicsTick(
            SubLevelPhysicsSystem physicsSystem,
            PhysicsPipeline pipeline,
            double timeStep) {
        final List<PendingImpulse> pending;
        final int requestedMicrosteps;
        synchronized (OffroadContinuousSuspensionPrototype.class) {
            requestedMicrosteps = enabled ? Math.max(1, microsteps) : 1;
            Map<ServerSubLevel, Accumulator> bySubLevel = PENDING.remove(physicsSystem);
            if (bySubLevel == null || bySubLevel.isEmpty()) {
                pending = List.of();
            } else {
                pending = new ArrayList<>(bySubLevel.size());
                for (Map.Entry<ServerSubLevel, Accumulator> entry : bySubLevel.entrySet()) {
                    Accumulator accumulator = entry.getValue();
                    pending.add(new PendingImpulse(
                            entry.getKey(),
                            new Vector3d(accumulator.linear),
                            new Vector3d(accumulator.angular)));
                }
            }
        }

        if (requestedMicrosteps <= 1 || pending.isEmpty()) {
            // If the prototype was disabled after capture but before the solve, preserve momentum rather
            // than silently dropping it.
            for (PendingImpulse impulse : pending) {
                applyFraction(physicsSystem, impulse, 1.0, true);
            }
            pipeline.physicsTick(timeStep);
            return;
        }

        final double fraction = 1.0 / requestedMicrosteps;
        final double microTimeStep = timeStep * fraction;

        for (int microstep = 0; microstep < requestedMicrosteps; microstep++) {
            boolean wakeUp = microstep == 0;
            for (PendingImpulse impulse : pending) {
                applyFraction(physicsSystem, impulse, fraction, wakeUp);
            }
            pipeline.physicsTick(microTimeStep);
        }
    }

    private static void applyFraction(
            SubLevelPhysicsSystem physicsSystem,
            PendingImpulse impulse,
            double fraction,
            boolean wakeUp) {
        ServerSubLevel subLevel = impulse.subLevel();
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }

        RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        if (handle == null || !handle.isValid()) {
            return;
        }

        handle.applyLinearAndAngularImpulse(
                new Vector3d(impulse.linear()).mul(fraction),
                new Vector3d(impulse.angular()).mul(fraction),
                wakeUp);
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad_continuous")
                                .then(Commands.literal("enabled")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    enabled = BoolArgumentType.getBool(context, "enabled");
                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("Offroad continuous wheel-force prototype: "
                                                                    + (enabled ? "ENABLED" : "DISABLED")),
                                                            false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("microsteps")
                                        .then(Commands.argument("microsteps", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> {
                                                    microsteps = IntegerArgumentType.getInteger(context, "microsteps");
                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("Offroad continuous wheel-force microsteps="
                                                                    + microsteps),
                                                            false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("status")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Offroad continuous wheel force: enabled="
                                                            + enabled + "; microsteps=" + microsteps),
                                                    false);
                                            return 1;
                                        }))
                                .then(Commands.literal("reset")
                                        .executes(context -> {
                                            enabled = true;
                                            microsteps = DEFAULT_MICROSTEPS;
                                            synchronized (OffroadContinuousSuspensionPrototype.class) {
                                                PENDING.clear();
                                            }
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Offroad continuous wheel force reset: enabled=true; microsteps="
                                                            + DEFAULT_MICROSTEPS),
                                                    false);
                                            return 1;
                                        })))
        );
    }

    private static final class Accumulator {
        private final Vector3d linear = new Vector3d();
        private final Vector3d angular = new Vector3d();
    }

    private record PendingImpulse(ServerSubLevel subLevel, Vector3d linear, Vector3d angular) {
    }
}
