package dev.antikytheramechanism.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** Client-only resolution of a managed child hit to the physical body manipulated by Simulated. */
public final class PhysicsStaffClientSelectionBridge {
    private static final double SCALE_EPSILON = 1.0E-6;

    private PhysicsStaffClientSelectionBridge() {
    }

    /**
     * Returns a foreign host selection for a managed child, or {@code null} when the child is rooted
     * in the normal world, unavailable, ambiguous, or otherwise unsafe to manipulate directly.
     *
     * <p>When Create currently owns the Frames, no physical Frame BlockEntity exists for the ordinary
     * host resolver to find. In that state the synchronized controlled-contraption controller is the
     * physical authority: if it belongs to a foreign Sable host, select that host and use the controller
     * block itself as the Staff pivot.</p>
     */
    public static @Nullable Selection resolve(ClientSubLevel child, Position childHit) {
        Selection create = resolveCreateController(child);
        if (create != null) {
            return create;
        }

        ManagedClientFrameHost.Binding binding = ManagedClientFrameHost.resolve(child);
        if (binding == null) {
            return null;
        }

        Vector3d worldHit = child.logicalPose().transformPosition(
                new Vector3d(childHit.x(), childHit.y(), childHit.z()),
                new Vector3d());
        Vector3d hostHit = binding.host().logicalPose().transformPositionInverse(worldHit, new Vector3d());
        return new Selection(
                binding.child(),
                binding.host(),
                binding.assemblyId(),
                binding,
                null,
                hostHit);
    }

    private static @Nullable Selection resolveCreateController(ClientSubLevel child) {
        UUID assemblyId = ManagedClientSubLevelIdentity.assemblyId(child);
        if (assemblyId == null) {
            return null;
        }

        Selection match = null;
        for (Entity candidate : child.getLevel().entitiesForRendering()) {
            if (!(candidate instanceof CreateContraptionClientAccess.EntityCarrier entityAccess)
                    || !(candidate instanceof CreateContraptionClientAccess.ControllerCarrier controllerAccess)) {
                continue;
            }
            Object contraption = entityAccess.getAntikytheraContraption();
            if (!(contraption instanceof CreateContraptionClientAccess.BlockCarrier blockAccess)
                    || CreateContraptionFrameBinding.find(blockAccess.getAntikytheraBlocks(), assemblyId) == null) {
                continue;
            }

            BlockPos controller = controllerAccess.getAntikytheraControllerPos();
            if (controller == null) {
                continue;
            }
            ClientSubLevel host = Sable.HELPER.getContainingClient(controller);
            if (host == null
                    || host == child
                    || host.isRemoved()
                    || ManagedClientSubLevelIdentity.isManaged(host)
                    || !hasUnitScale(host)) {
                continue;
            }

            Vector3d pivot = new Vector3d(
                    controller.getX() + 0.5,
                    controller.getY() + 0.5,
                    controller.getZ() + 0.5);
            Selection resolved = new Selection(
                    child,
                    host,
                    assemblyId,
                    null,
                    controller.immutable(),
                    pivot);
            if (match != null
                    && (match.host() != host || !match.fixedPivot().equals(controller))) {
                // Never guess when two live Create actors claim the same managed child differently.
                return null;
            }
            match = resolved;
        }
        return match;
    }

    private static boolean hasUnitScale(SubLevel subLevel) {
        Vector3dc scale = subLevel.logicalPose().scale();
        return Math.abs(scale.x() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.y() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.z() - 1.0) <= SCALE_EPSILON;
    }

    public record Selection(
            ClientSubLevel child,
            ClientSubLevel host,
            UUID assemblyId,
            @Nullable ManagedClientFrameHost.Binding frameBinding,
            @Nullable BlockPos fixedPivot,
            Vector3d hostHit) {

        /**
         * Static managed children use the exact owning Frame. A child carried by Create uses the
         * synchronized stationary controller block that owns the contraption.
         */
        public BlockPos frameForMiniBlock(BlockPos childPlotBlock) {
            if (fixedPivot != null) {
                return fixedPivot;
            }
            if (frameBinding == null) {
                throw new IllegalStateException("Managed Physics Staff selection has no pivot authority");
            }
            return frameBinding.frameForChildPlotBlock(childPlotBlock);
        }

        public Vec3 hostHitLocation() {
            return new Vec3(hostHit.x, hostHit.y, hostHit.z);
        }
    }
}
