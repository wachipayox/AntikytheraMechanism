package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Projects a physical interaction aimed at a pose-driven managed mini child onto the foreign
 * Sable body that actually carries its Mechanism Frame.
 *
 * <p>The managed child remains the authoritative coordinate space for gameplay and rendering.
 * Only physical quantities are re-expressed child -> world -> host. ROOT-hosted children are
 * deliberately not projected: the root world is the fixed reaction body.</p>
 */
public final class HostedMiniForceProjection {
    private HostedMiniForceProjection() {
    }

    public static @Nullable ServerSubLevel foreignHost(
            ServerLevel level,
            ServerSubLevel logicalBody) {
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(logicalBody);
        if (ownerId == null) {
            return null;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssembly(ownerId)
                .orElse(null);
        if (assembly == null) {
            return null;
        }

        MechanismAssemblyHost.Resolution resolution =
                MechanismAssemblyHost.resolve(level, assembly.origin());
        if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || resolution.subLevel() == null
                || resolution.subLevel() == logicalBody
                || resolution.subLevel().isRemoved()) {
            return null;
        }
        return resolution.subLevel();
    }

    public static @Nullable Projection project(
            ServerLevel level,
            ServerSubLevel logicalBody,
            Vector3dc logicalPlotPosition) {
        ServerSubLevel physicalBody = foreignHost(level, logicalBody);
        if (physicalBody == null) {
            return null;
        }

        Vector3d worldPosition = logicalBody.logicalPose()
                .transformPosition(logicalPlotPosition, new Vector3d());
        Vector3d physicalPlotPosition = physicalBody.logicalPose()
                .transformPositionInverse(worldPosition, new Vector3d());
        return new Projection(
                logicalBody,
                physicalBody,
                worldPosition,
                physicalPlotPosition);
    }

    /**
     * Resolves a plot-space point without requiring the caller to know which SubLevel owns it.
     * Useful for Sable helpers such as point-velocity queries.
     */
    public static @Nullable Projection projectContaining(
            ServerLevel level,
            Vector3dc plotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, plotPosition);
        if (!(containing instanceof ServerSubLevel logicalBody)) {
            return null;
        }
        return project(level, logicalBody, plotPosition);
    }

    /**
     * Re-expresses a local force/impulse/torque vector in the physical host's local basis.
     *
     * <p>Use Pose3d normal transforms rather than quaternion-only rotation. Sable's force APIs use
     * plot-local vectors whose magnitude is scale-aware; composing normal -> world -> inverse-normal
     * preserves the world-space magnitude for a 0.5 managed child and a unit-scale foreign host.</p>
     */
    public static Vector3d transformLocalVector(
            ServerSubLevel logicalBody,
            ServerSubLevel physicalBody,
            Vector3dc logicalVector) {
        Vector3d worldVector = logicalBody.logicalPose()
                .transformNormal(logicalVector, new Vector3d());
        return physicalBody.logicalPose()
                .transformNormalInverse(worldVector, new Vector3d());
    }

    public record Projection(
            ServerSubLevel logicalBody,
            ServerSubLevel physicalBody,
            Vector3d worldPosition,
            Vector3d physicalPlotPosition) {
    }
}
