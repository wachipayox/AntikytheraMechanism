package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.util.SableMathUtils;
import net.minecraft.core.BlockPos;
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
 * orientation, and composes the result into the host's MergedMassTracker. Physical host resolution
 * is shared with force/collision adapters through {@link HostedMiniPhysicalAttachment}.</p>
 *
 * <p>For a FOREIGN host the contribution is calculated entirely in stable host-local coordinates.
 * Reconstructing the same local distribution through child -> world -> host every physics substep
 * makes tiny floating-point pose changes look like real COM/inertia changes. Sable responds to any
 * exact COM change by moving the rigid body to preserve its rotation point, which can feed energy
 * into a resting terrain contact. Keeping this calculation local makes rigid host motion irrelevant
 * to its own mass distribution.</p>
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
            return new MergedMass(
                    baseMass,
                    new Vector3d(baseCenter),
                    new Matrix3d(baseInertia));
        }

        double mass = baseMass;
        Vector3d center = new Vector3d(baseCenter);
        Matrix3d inertia = new Matrix3d(baseInertia);
        MechanismAssemblyManager manager =
                MechanismAssemblyManager.get(host.getLevel());

        for (MechanismAssembly assembly : manager.assemblies()) {
            if (manager.isContentRecoveryLocked(assembly.id())) {
                continue;
            }

            ServerSubLevel child =
                    MechanismSubLevelService.get(host.getLevel(), assembly);
            if (child == null
                    || child.isRemoved()
                    || !child.getUniqueId().equals(assembly.subLevelId())) {
                continue;
            }

            HostedMiniPhysicalAttachment.Attachment attachment =
                    HostedMiniPhysicalAttachment.resolve(
                            host.getLevel(),
                            assembly,
                            child);
            if (attachment == null
                    || !host.getUniqueId().equals(
                            attachment.physicalBody().getUniqueId())) {
                continue;
            }

            Contribution contribution = contribution(host, child, assembly);
            if (contribution == null
                    || contribution.mass() <= MASS_EPSILON) {
                continue;
            }

            double combinedMass = mass + contribution.mass();
            if (!Double.isFinite(combinedMass)
                    || combinedMass <= MASS_EPSILON) {
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
                    new Vector3d(contribution.centerOfMass())
                            .sub(combinedCenter),
                    contribution.mass(),
                    combinedInertia);

            mass = combinedMass;
            center = combinedCenter;
            inertia = combinedInertia;
        }

        return new MergedMass(mass, center, inertia);
    }

    private static Contribution contribution(
            ServerSubLevel host,
            ServerSubLevel child,
            MechanismAssembly assembly) {
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
        double volumeScale =
                linearScale * linearScale * linearScale;
        double inertiaScale =
                volumeScale * linearScale * linearScale;

        double scaledMass = payload.mass() * volumeScale;
        if (!Double.isFinite(scaledMass)
                || scaledMass <= MASS_EPSILON) {
            return null;
        }

        // AssemblyPoseDriver maps this stable child-plot point to the origin Frame's center. The
        // assembly pose is already expressed in FOREIGN-host coordinates, so transform the payload
        // relative to the same anchor without ever involving either body's changing world pose.
        BlockPos plotCenter = child.getPlot().getCenterBlock();
        Vector3d childAnchor = new Vector3d(
                plotCenter.getX() + 1.0,
                plotCenter.getY() + 1.0,
                plotCenter.getZ() + 1.0);
        AssemblyPose localPose = MechanismAssemblyHost.hostLocalPose(host.getLevel(), assembly);
        Quaterniond childToHost = localPose.orientation(new Quaterniond()).normalize();

        Vector3d hostCenter = new Vector3d(payload.centerOfMass())
                .sub(childAnchor)
                .mul(linearScale);
        childToHost.transform(hostCenter);
        hostCenter.add(localPose.anchor(new Vector3d()));

        Matrix3d scaledInertia =
                new Matrix3d(payload.inertiaTensor()).scale(inertiaScale);
        Matrix3d hostInertia = new Matrix3d()
                .rotateLocal(childToHost.conjugate(new Quaterniond()))
                .mulLocal(scaledInertia)
                .rotateLocal(childToHost);

        return new Contribution(
                scaledMass,
                hostCenter,
                hostInertia);
    }

    private static boolean isUniformPositiveScale(Vector3dc scale) {
        return Double.isFinite(scale.x())
                && Double.isFinite(scale.y())
                && Double.isFinite(scale.z())
                && scale.x() > 0.0
                && Math.abs(scale.x() - scale.y()) <= SCALE_EPSILON
                && Math.abs(scale.y() - scale.z()) <= SCALE_EPSILON;
    }

    public record MergedMass(
            double mass,
            Vector3d centerOfMass,
            Matrix3d inertiaTensor) {
    }

    private record Contribution(
            double mass,
            Vector3d centerOfMass,
            Matrix3d inertiaTensor) {
    }
}
