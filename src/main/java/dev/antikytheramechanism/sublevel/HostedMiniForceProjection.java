package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Compatibility facade for force adapters. Ownership and physical-host resolution live in
 * {@link HostedMiniPhysicalAttachment} so collision, mass and forces share one authority model.
 */
public final class HostedMiniForceProjection {
    private HostedMiniForceProjection() {
    }

    public static @Nullable ServerSubLevel foreignHost(ServerLevel level, ServerSubLevel logicalBody) {
        HostedMiniPhysicalAttachment.Attachment attachment =
                HostedMiniPhysicalAttachment.resolve(level, logicalBody);
        return attachment == null ? null : attachment.physicalBody();
    }

    public static @Nullable Projection project(
            ServerLevel level,
            ServerSubLevel logicalBody,
            Vector3dc logicalPlotPosition) {
        HostedMiniPhysicalAttachment.Attachment attachment =
                HostedMiniPhysicalAttachment.resolve(level, logicalBody);
        if (attachment == null) {
            return null;
        }

        Vector3d worldPosition =
                attachment.logicalToWorld(logicalPlotPosition, new Vector3d());
        Vector3d physicalPlotPosition =
                attachment.worldToPhysical(worldPosition, new Vector3d());
        return new Projection(
                attachment.logicalBody(),
                attachment.physicalBody(),
                worldPosition,
                physicalPlotPosition);
    }

    public static @Nullable Projection projectContaining(
            ServerLevel level,
            Vector3dc plotPosition) {
        HostedMiniPhysicalAttachment.Attachment attachment =
                HostedMiniPhysicalAttachment.resolveContaining(level, plotPosition);
        if (attachment == null) {
            return null;
        }

        Vector3d worldPosition = attachment.logicalToWorld(plotPosition, new Vector3d());
        Vector3d physicalPlotPosition =
                attachment.worldToPhysical(worldPosition, new Vector3d());
        return new Projection(
                attachment.logicalBody(),
                attachment.physicalBody(),
                worldPosition,
                physicalPlotPosition);
    }

    public static Vector3d transformLocalVector(
            ServerSubLevel logicalBody,
            ServerSubLevel physicalBody,
            Vector3dc logicalVector) {
        Vector3d worldVector =
                logicalBody.logicalPose().transformNormal(logicalVector, new Vector3d());
        return physicalBody.logicalPose().transformNormalInverse(worldVector, new Vector3d());
    }

    public record Projection(
            ServerSubLevel logicalBody,
            ServerSubLevel physicalBody,
            Vector3d worldPosition,
            Vector3d physicalPlotPosition) {
    }
}
