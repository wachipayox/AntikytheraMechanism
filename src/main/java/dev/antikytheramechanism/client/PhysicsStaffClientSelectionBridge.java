package dev.antikytheramechanism.client;

import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** Client-only resolution of a managed child hit to the physical Frame host manipulated by Simulated. */
public final class PhysicsStaffClientSelectionBridge {
    private static final String MANAGED_NAME_PREFIX = "antikythera-";
    private static final double CENTER_EPSILON = 1.0E-3;
    private static final double SCALE_EPSILON = 1.0E-6;

    private PhysicsStaffClientSelectionBridge() {
    }

    /**
     * Returns a foreign host selection for a managed child, or {@code null} when the child is rooted
     * in the normal world, unavailable, ambiguous, or otherwise unsafe to manipulate directly.
     */
    public static @Nullable Selection resolve(ClientSubLevel child, Position childHit) {
        if (!ManagedClientSubLevelIdentity.isManaged(child)) {
            return null;
        }
        UUID assemblyId = assemblyId(child);
        if (assemblyId == null) {
            return null;
        }

        Level level = child.getLevel();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        BlockPos plotCenter = child.getPlot().getCenterBlock();
        Vector3d childOriginCenter = new Vector3d(
                plotCenter.getX() + 1.0,
                plotCenter.getY() + 1.0,
                plotCenter.getZ() + 1.0);
        Vector3d worldOriginCenter = child.logicalPose().transformPosition(childOriginCenter, new Vector3d());
        Vector3d worldHit = child.logicalPose().transformPosition(
                new Vector3d(childHit.x(), childHit.y(), childHit.z()),
                new Vector3d());

        Selection match = null;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ClientSubLevel host)
                    || host == child
                    || host.isRemoved()
                    || ManagedClientSubLevelIdentity.isManaged(host)
                    || !hasUnitScale(host)) {
                continue;
            }

            Vector3d hostOriginCenter = host.logicalPose().transformPositionInverse(worldOriginCenter, new Vector3d());
            BlockPos originFrame = matchingFrame(level, host, hostOriginCenter, assemblyId);
            if (originFrame == null) {
                continue;
            }
            if (match != null && match.host() != host) {
                // Never guess between two overlapping physical hosts.
                return null;
            }

            Vector3d hostHit = host.logicalPose().transformPositionInverse(worldHit, new Vector3d());
            match = new Selection(child, host, assemblyId, originFrame, hostHit);
        }

        // A managed child with no foreign match is intentionally not selectable. This includes the
        // normal root-world case: the child is pose-driven by its Frame and is not an independent body.
        return match;
    }

    private static @Nullable UUID assemblyId(ClientSubLevel child) {
        String name = child.getName();
        if (name == null || !name.startsWith(MANAGED_NAME_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(MANAGED_NAME_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean hasUnitScale(SubLevel subLevel) {
        Vector3dc scale = subLevel.logicalPose().scale();
        return Math.abs(scale.x() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.y() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.z() - 1.0) <= SCALE_EPSILON;
    }

    private static @Nullable BlockPos matchingFrame(
            Level level,
            @Nullable ClientSubLevel expectedHost,
            Vector3dc center,
            UUID assemblyId) {
        BlockPos frame = BlockPos.containing(center.x(), center.y(), center.z());
        if (Math.abs(center.x() - (frame.getX() + 0.5)) > CENTER_EPSILON
                || Math.abs(center.y() - (frame.getY() + 0.5)) > CENTER_EPSILON
                || Math.abs(center.z() - (frame.getZ() + 0.5)) > CENTER_EPSILON) {
            return null;
        }

        ClientSubLevel containing = Sable.HELPER.getContainingClient(frame);
        if (containing != expectedHost
                || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())
                || !(level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity frameEntity)
                || !assemblyId.equals(frameEntity.getAssemblyId())) {
            return null;
        }
        return frame.immutable();
    }

    public record Selection(
            ClientSubLevel child,
            ClientSubLevel host,
            UUID assemblyId,
            BlockPos originFrame,
            Vector3d hostHit) {

        /** Resolves the exact Frame containing the mini block that was hit, falling back to origin. */
        public BlockPos frameForMiniBlock(BlockPos childPlotBlock) {
            BlockPos plotCenter = child.getPlot().getCenterBlock();
            BlockPos mini = childPlotBlock.subtract(plotCenter);
            int frameX = Math.floorDiv(mini.getX(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            int frameY = Math.floorDiv(mini.getY(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            int frameZ = Math.floorDiv(mini.getZ(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);

            Vector3d childFrameCenter = new Vector3d(
                    plotCenter.getX() + frameX * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + 1.0,
                    plotCenter.getY() + frameY * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + 1.0,
                    plotCenter.getZ() + frameZ * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + 1.0);
            Vector3d worldFrameCenter = child.logicalPose().transformPosition(childFrameCenter, new Vector3d());
            Vector3d hostFrameCenter = host.logicalPose().transformPositionInverse(worldFrameCenter, new Vector3d());
            BlockPos resolved = matchingFrame(child.getLevel(), host, hostFrameCenter, assemblyId);
            return resolved != null ? resolved : originFrame;
        }

        public Vec3 hostHitLocation() {
            return new Vec3(hostHit.x, hostHit.y, hostHit.z);
        }
    }
}
