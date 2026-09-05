package dev.antikytheramechanism.compat.simulated;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot diagnostics for the Simulated Physics Staff lock path on scaled Sable bodies.
 *
 * <p>The fixed constraint should be translation-neutral: the world-side anchor is the body's
 * current native position and the body-side anchor resolves to local zero when rotationPoint and
 * the mass tracker's center of mass agree. If a scaled body still jumps, capturing both Sable's
 * logical pose and Rapier's native pose before and after the first solver pass tells us which
 * invariant actually breaks at runtime instead of compensating the symptom blindly.</p>
 */
public final class PhysicsStaffLockDiagnostics {
    private static final double SCALE_EPSILON = 1.0E-9;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PhysicsStaffLockDiagnostics() {
    }

    public static boolean isScaled(ServerSubLevel subLevel) {
        Vector3dc scale = subLevel.logicalPose().scale();
        return Math.abs(scale.x() - 1.0) > SCALE_EPSILON
                || Math.abs(scale.y() - 1.0) > SCALE_EPSILON
                || Math.abs(scale.z() - 1.0) > SCALE_EPSILON;
    }

    public static void begin(String route, ServerSubLevel subLevel) {
        State before = capture(subLevel);
        PENDING.put(subLevel.getUniqueId(), new Pending(route, before));
        logState("before", route, subLevel, before);
    }

    public static void immediate(String route, ServerSubLevel subLevel) {
        Pending pending = PENDING.get(subLevel.getUniqueId());
        if (pending == null) {
            return;
        }
        State state = capture(subLevel);
        logDelta("immediate", route, subLevel, pending.before(), state);
    }

    public static void cancel(ServerSubLevel subLevel) {
        PENDING.remove(subLevel.getUniqueId());
    }

    /** Called at the tail of Sable's post-solver pose writeback. */
    public static void afterSolver(ServerSubLevelContainer container) {
        if (PENDING.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            SubLevel candidate = container.getSubLevel(entry.getKey());
            if (!(candidate instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
                PENDING.remove(entry.getKey());
                continue;
            }

            Pending pending = PENDING.remove(entry.getKey());
            if (pending == null) {
                continue;
            }
            State after = capture(subLevel);
            logDelta("after_solver", pending.route(), subLevel, pending.before(), after);
        }
    }

    private static State capture(ServerSubLevel subLevel) {
        Pose3d logical = subLevel.logicalPose();
        Vector3dc massCenter = subLevel.getMassTracker().getCenterOfMass();
        Vector3d centerOfMass = massCenter != null
                ? new Vector3d(massCenter)
                : new Vector3d(Double.NaN, Double.NaN, Double.NaN);

        Pose3d nativePose = new Pose3d();
        try {
            ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(subLevel.getLevel());
            if (container != null) {
                PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
                pipeline.readPose(subLevel, nativePose);
            } else {
                nativePose.position().set(Double.NaN);
            }
        } catch (Throwable throwable) {
            nativePose.position().set(Double.NaN);
            AntikytheraMechanism.LOGGER.warn(
                    "[PhysicsStaffLockDiag] failed to read native pose for {}",
                    subLevel.getUniqueId(),
                    throwable);
        }

        return new State(
                new Vector3d(logical.position()),
                new Quaterniond(logical.orientation()),
                new Vector3d(logical.rotationPoint()),
                new Vector3d(logical.scale()),
                centerOfMass,
                new Vector3d(nativePose.position()),
                new Quaterniond(nativePose.orientation()));
    }

    private static void logState(String stage, String route, ServerSubLevel subLevel, State state) {
        AntikytheraMechanism.LOGGER.info(
                "[PhysicsStaffLockDiag] stage={} route={} id={} tick={} scale={} logicalPos={} nativePos={} rotationPoint={} massCoM={} rpMinusCoM={} logicalMinusNative={} orientation={} nativeOrientation={}",
                stage,
                route,
                subLevel.getUniqueId(),
                subLevel.getLevel().getGameTime(),
                state.scale(),
                state.logicalPosition(),
                state.nativePosition(),
                new Vector3d(state.rotationPoint()),
                state.centerOfMass(),
                new Vector3d(state.rotationPoint()).sub(state.centerOfMass()),
                new Vector3d(state.logicalPosition()).sub(state.nativePosition()),
                state.orientation(),
                state.nativeOrientation());
    }

    private static void logDelta(
            String stage,
            String route,
            ServerSubLevel subLevel,
            State before,
            State after) {
        AntikytheraMechanism.LOGGER.info(
                "[PhysicsStaffLockDiag] stage={} route={} id={} tick={} scale={} logicalDelta={} nativeDelta={} rotationPointDelta={} massCoMDelta={} rpMinusCoM={} logicalMinusNative={} orientationDeltaRad={}",
                stage,
                route,
                subLevel.getUniqueId(),
                subLevel.getLevel().getGameTime(),
                after.scale(),
                new Vector3d(after.logicalPosition()).sub(before.logicalPosition()),
                new Vector3d(after.nativePosition()).sub(before.nativePosition()),
                new Vector3d(after.rotationPoint()).sub(before.rotationPoint()),
                new Vector3d(after.centerOfMass()).sub(before.centerOfMass()),
                new Vector3d(after.rotationPoint()).sub(after.centerOfMass()),
                new Vector3d(after.logicalPosition()).sub(after.nativePosition()),
                before.orientation().difference(after.orientation(), new Quaterniond()).angle());
    }

    private record Pending(String route, State before) {
    }

    private record State(
            Vector3d logicalPosition,
            Quaterniond orientation,
            Vector3d rotationPoint,
            Vector3d scale,
            Vector3d centerOfMass,
            Vector3d nativePosition,
            Quaterniond nativeOrientation) {
    }
}
