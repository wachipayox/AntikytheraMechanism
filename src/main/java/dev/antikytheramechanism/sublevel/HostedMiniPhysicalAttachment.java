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
 * Resolves the one rigid body that is physically authoritative for a managed mini child.
 *
 * <p>A managed child remains the gameplay/rendering level for its real mini blocks. Once its
 * Mechanism Frames live inside another Sable SubLevel, however, the foreign host is authoritative
 * for rigid dynamics. Mass, forces and collision adapters must all resolve that relationship through
 * this class rather than each reconstructing ownership/host logic independently.</p>
 */
public final class HostedMiniPhysicalAttachment {
    private HostedMiniPhysicalAttachment() {
    }

    public static @Nullable Attachment resolve(ServerLevel level, ServerSubLevel logicalBody) {
        if (logicalBody == null || logicalBody.isRemoved()) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(logicalBody);
        if (ownerId == null) {
            return null;
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null
                || assembly.subLevelId() == null
                || !logicalBody.getUniqueId().equals(assembly.subLevelId())) {
            return null;
        }

        return resolve(level, assembly, logicalBody);
    }

    public static @Nullable Attachment resolve(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel logicalBody) {
        if (assembly == null || logicalBody == null || logicalBody.isRemoved()) {
            return null;
        }

        MechanismAssemblyHost.Resolution resolution =
                MechanismAssemblyHost.resolve(level, assembly.origin());
        ServerSubLevel physicalBody = resolution.subLevel();
        if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || physicalBody == null
                || physicalBody == logicalBody
                || physicalBody.isRemoved()) {
            return null;
        }

        return new Attachment(assembly, logicalBody, physicalBody);
    }

    public static @Nullable Attachment resolveContaining(ServerLevel level, Vector3dc plotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, plotPosition);
        if (!(containing instanceof ServerSubLevel logicalBody)) {
            return null;
        }
        return resolve(level, logicalBody);
    }

    public record Attachment(
            MechanismAssembly assembly,
            ServerSubLevel logicalBody,
            ServerSubLevel physicalBody) {

        public Vector3d logicalToWorld(Vector3dc logicalPlotPosition, Vector3d destination) {
            return logicalBody.logicalPose().transformPosition(logicalPlotPosition, destination);
        }

        public Vector3d worldToPhysical(Vector3dc worldPosition, Vector3d destination) {
            return physicalBody.logicalPose().transformPositionInverse(worldPosition, destination);
        }

        public Vector3d logicalToPhysical(Vector3dc logicalPlotPosition, Vector3d destination) {
            Vector3d world = logicalToWorld(logicalPlotPosition, new Vector3d());
            return worldToPhysical(world, destination);
        }

        public Vector3d logicalVectorToPhysical(Vector3dc logicalVector, Vector3d destination) {
            Vector3d worldVector =
                    logicalBody.logicalPose().transformNormal(logicalVector, new Vector3d());
            return physicalBody.logicalPose().transformNormalInverse(worldVector, destination);
        }
    }
}
