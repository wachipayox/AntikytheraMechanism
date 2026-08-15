package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.FloatingBlockControllerAccessor;
import dev.antikytheramechanism.mixin.FloatingBlockDataAccessor;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockCluster;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockData;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects Sable's already-maintained floating-material aggregates from managed mini children into
 * their physical foreign host.
 *
 * <p>The child controller remains authoritative for material grouping and block-change bookkeeping.
 * Antikythera transforms the aggregate moments into host-local coordinates and applies the child's
 * physical scale. The host's native FloatingBlockController then computes lift, drag, pressure,
 * prevent-self-lift and torque using the host's own mass/inertia/velocity.</p>
 */
public final class HostedMiniFloatingMaterialBridge {
    private static final double SCALE_EPSILON = 1.0E-6;
    private static final ThreadLocal<Map<ServerSubLevel, List<FloatingClusterContainer>>> PREPARED =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private HostedMiniFloatingMaterialBridge() {
    }

    public static List<FloatingClusterContainer> contributionFor(ServerSubLevel host) {
        if (host == null || host.isRemoved() || MechanismSubLevelService.getOwnerAssemblyId(host) != null) {
            return List.of();
        }
        Map<ServerSubLevel, List<FloatingClusterContainer>> prepared = PREPARED.get();
        return prepared.computeIfAbsent(host, HostedMiniFloatingMaterialBridge::buildContribution);
    }

    public static boolean hasContribution(ServerSubLevel host) {
        return !contributionFor(host).isEmpty();
    }

    public static void clear(ServerSubLevel host) {
        Map<ServerSubLevel, List<FloatingClusterContainer>> prepared = PREPARED.get();
        prepared.remove(host);
        if (prepared.isEmpty()) {
            PREPARED.remove();
        }
    }

    private static List<FloatingClusterContainer> buildContribution(ServerSubLevel host) {
        if (host.getMassTracker() == null || host.getMassTracker().getCenterOfMass() == null) {
            return List.of();
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(host.getLevel());
        FloatingClusterContainer synthetic = new FloatingClusterContainer();

        for (MechanismAssembly assembly : manager.assemblies()) {
            if (manager.isContentRecoveryLocked(assembly.id())
                    || manager.pendingPistonMove(assembly.id()).isPresent()
                    || manager.pendingContraptionMove(assembly.id()).isPresent()
                    || manager.pendingFrameEvacuation(assembly.id()).isPresent()) {
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
            if (child != null
                    && !child.isRemoved()
                    && child.getUniqueId().equals(assembly.subLevelId())) {
                appendChild(host, child, synthetic);
            }
        }
        return synthetic.clusters.isEmpty() ? List.of() : List.of(synthetic);
    }

    private static void appendChild(
            ServerSubLevel host,
            ServerSubLevel child,
            FloatingClusterContainer target) {
        if (child.getMassTracker() == null || child.getMassTracker().getCenterOfMass() == null) {
            return;
        }

        Vector3dc scale = child.logicalPose().scale();
        if (!isUniformPositiveScale(scale)) {
            return;
        }
        double linearScale = scale.x();
        double volumeScale = linearScale * linearScale * linearScale;
        double secondMomentScale = volumeScale * linearScale * linearScale;

        FloatingBlockController controller = child.getFloatingBlockController();
        FloatingBlockControllerAccessor controllerAccess = (FloatingBlockControllerAccessor) controller;
        // The host may tick before the child in Sable's iteration order. Flush the child's queued
        // block changes once here so the aggregate always reflects the current mini states.
        controllerAccess.antikytheramechanism$processBlockChanges();
        FloatingClusterContainer source = controllerAccess.antikytheramechanism$getSublevelContainer();
        if (source.clusters.isEmpty()) {
            return;
        }

        Vector3d childCenterWorld = child.logicalPose().transformPosition(
                child.getMassTracker().getCenterOfMass(),
                new Vector3d());
        Vector3d childCenterInHost = host.logicalPose().transformPositionInverse(
                childCenterWorld,
                new Vector3d());
        Vector3d translation = childCenterInHost.sub(
                host.getMassTracker().getCenterOfMass(),
                new Vector3d());

        Quaterniond childToHost = new Quaterniond(host.logicalPose().orientation())
                .conjugate()
                .mul(child.logicalPose().orientation())
                .normalize();

        for (FloatingBlockCluster sourceCluster : source.clusters) {
            FloatingBlockData sourceData = sourceCluster.getBlockData();
            FloatingBlockDataAccessor sourceAccess = (FloatingBlockDataAccessor) sourceData;
            double sourceScale = sourceAccess.antikytheramechanism$getTotalScale();
            if (!Double.isFinite(sourceScale) || sourceScale <= 0.0) {
                continue;
            }

            double targetScale = sourceScale * volumeScale;
            Vector3d weightedPosition = childToHost.transform(
                    new Vector3d(sourceAccess.antikytheramechanism$getWeightedPosition())
                            .mul(volumeScale * linearScale));
            weightedPosition.fma(targetScale, translation);

            Matrix3d scaledMoment = new Matrix3d(
                    sourceAccess.antikytheramechanism$getOuterProduct())
                    .scale(secondMomentScale);
            Matrix3d hostMoment = new Matrix3d()
                    .rotateLocal(childToHost.conjugate(new Quaterniond()))
                    .mulLocal(scaledMoment)
                    .rotateLocal(childToHost);

            FloatingBlockCluster projected = new FloatingBlockCluster(sourceCluster.getMaterial());
            FloatingBlockDataAccessor projectedAccess =
                    (FloatingBlockDataAccessor) projected.getBlockData();
            projectedAccess.antikytheramechanism$setTotalScale(targetScale);
            projectedAccess.antikytheramechanism$setBlockCount(
                    sourceAccess.antikytheramechanism$getBlockCount());
            projectedAccess.antikytheramechanism$getWeightedPosition().set(weightedPosition);
            projectedAccess.antikytheramechanism$getOuterProduct().set(hostMoment);
            target.clusters.add(projected);
        }
    }

    private static boolean isUniformPositiveScale(Vector3dc scale) {
        return Double.isFinite(scale.x())
                && Double.isFinite(scale.y())
                && Double.isFinite(scale.z())
                && scale.x() > 0.0
                && Math.abs(scale.x() - scale.y()) <= SCALE_EPSILON
                && Math.abs(scale.y() - scale.z()) <= SCALE_EPSILON;
    }
}
