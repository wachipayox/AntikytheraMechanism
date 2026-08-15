package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.util.SableMathUtils;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Projects the already-computed Sable mass distribution of managed Antikythera children into the
 * foreign Sable body that physically contains their Mechanism Frames.
 *
 * <p>The child MassTracker remains authoritative for mini block mass, center of mass and inertia.
 * Antikythera only removes its artificial structural stabilizer, applies the child Pose3d scale and
 * orientation, and composes the result into the host's MergedMassTracker.</p>
 */
public final class HostedMiniMassBridge {
    private static final double SCALE_EPSILON = 1.0E-6;
    private static final double MASS_EPSILON = 1.0E-10;

    private HostedMiniMassBridge() {
    }

    public static MergedMass mergeInto(
            ServerSubLevel host,
            double baseMass,
            Vector3dc baseCenter,
            Matrix3dc baseInertia) {
        if (host == null
                || host.isRemoved()
                || baseCenter == null
                || MechanismSubLevelService.getOwnerAssemblyId(host) != null) {
            return new MergedMass(baseMass, new Vector3d(baseCenter), new Matrix3d(baseInertia));
        }

        double mass = baseMass;
        Vector3d center = new Vector3d(baseCenter);
        Matrix3d inertia = new Matrix3d(baseInertia);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(host.getLevel());

        for (MechanismAssembly assembly : manager.assemblies()) {
            if (manager.isContentRecoveryLocked(assembly.id())) {
                continue;
            }

            MechanismAssemblyHost.Resolution resolution = MechanismAssemblyHost.resolve(
                    host.getLevel(), assembly.origin());
            if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                    || resolution.subLevel() == null
                    || !host.getUniqueId().equals(resolution.subLevel().getUniqueId())) {
                continue;
            }

            ServerSubLevel child = MechanismSubLevelService.get(host.getLevel(), assembly);
            if (child == null
                    || child.isRemoved()
                    || !child.getUniqueId().equals(assembly.subLevelId())) {
                continue;
            }

            Contribution contribution = contribution(host, child);
            if (contribution == null || contribution.mass() <= MASS_EPSILON) {
                continue;
            }

            double combinedMass = mass + contribution.mass();
            if (!Double.isFinite(combinedMass) || combinedMass <= MASS_EPSILON) {
                continue;
            }

            Vector3d combinedCenter = new Vector3d(center)
                    .mul(mass)
                    .fma(contribution.mass(), contribution.centerOfMass())
                    .div(combinedMass);

            Matrix3d combinedInertia = new Matrix3d(inertia);
            SableMathUtils.fmaInertiaTensor(
                    new Vector3d(center).sub(combinedCenter),
                    mass,
                    combinedInertia);
            combinedInertia.add(contribution.inertiaTensor());
            SableMathUtils.fmaInertiaTensor(
                    new Vector3d(contribution.centerOfMass()).sub(combinedCenter),
                    contribution.mass(),
                    combinedInertia);

            mass = combinedMass;
            center = combinedCenter;
            inertia = combinedInertia;
        }

        return new MergedMass(mass, center, inertia);
    }

    private static Contribution contribution(ServerSubLevel host, ServerSubLevel child) {
        ManagedSubLevelMassPolicy.PayloadMassData payload =
                ManagedSubLevelMassPolicy.payloadMassData(child);
        if (payload == null) {
            return null;
        }

        Vector3dc scale = child.logicalPose().scale();
        if (!isUniformPositiveScale(scale)) {
            return null;
        }
        double linearScale = scale.x();
        double volumeScale = linearScale * linearScale * linearScale;
        double inertiaScale = volumeScale * linearScale * linearScale;

        double scaledMass = payload.mass() * volumeScale;
        if (!Double.isFinite(scaledMass) || scaledMass <= MASS_EPSILON) {
            return null;
        }

        Vector3d worldCenter = child.logicalPose().transformPosition(
                payload.centerOfMass(),
                new Vector3d());
        Vector3d hostCenter = host.logicalPose().transformPositionInverse(
                worldCenter,
                new Vector3d());

        Quaterniond childToHost = new Quaterniond(host.logicalPose().orientation())
                .conjugate()
                .mul(child.logicalPose().orientation())
                .normalize();

        Matrix3d scaledInertia = new Matrix3d(payload.inertiaTensor()).scale(inertiaScale);
        // Same local-tensor basis change used by Sable's own MergedMassTracker for contraptions:
        // R * I * R^-1, expressed in the host's local axes.
        Matrix3d hostInertia = new Matrix3d()
                .rotateLocal(childToHost.conjugate(new Quaterniond()))
                .mulLocal(scaledInertia)
                .rotateLocal(childToHost);

        return new Contribution(scaledMass, hostCenter, hostInertia);
    }

    private static boolean isUniformPositiveScale(Vector3dc scale) {
        return Double.isFinite(scale.x())
                && Double.isFinite(scale.y())
                && Double.isFinite(scale.z())
                && scale.x() > 0.0
                && Math.abs(scale.x() - scale.y()) <= SCALE_EPSILON
                && Math.abs(scale.y() - scale.z()) <= SCALE_EPSILON;
    }

    public record MergedMass(double mass, Vector3d centerOfMass, Matrix3d inertiaTensor) {
    }

    private record Contribution(double mass, Vector3d centerOfMass, Matrix3d inertiaTensor) {
    }
}
