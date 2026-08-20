package dev.antikytheramechanism.compat.offroad;

/**
 * JNI bridge exported only by Antikythera's diagnostic Sable Rapier native.
 *
 * <p>Forces and torques are expressed in world coordinates and are added to Rapier's persistent
 * external-force accumulator. Callers that want a one-step force must add the exact negative values
 * again after the step.</p>
 */
public final class OffroadNativeForceBridge {
    private OffroadNativeForceBridge() {
    }

    public static native void addWorldForceAndTorque(
            long sceneHandle,
            int bodyId,
            double forceX,
            double forceY,
            double forceZ,
            double torqueX,
            double torqueY,
            double torqueZ,
            boolean wakeUp);
}
