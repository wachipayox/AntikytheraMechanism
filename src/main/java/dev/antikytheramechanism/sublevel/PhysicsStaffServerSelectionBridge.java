package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** Server-authoritative backstop for Physics Staff packets that still name a managed child. */
public final class PhysicsStaffServerSelectionBridge {
    private PhysicsStaffServerSelectionBridge() {
    }

    public static @Nullable Selection resolveManaged(ServerLevel level, UUID selectedId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        SubLevel selected = container.getSubLevel(selectedId);
        if (!(selected instanceof ServerSubLevel child) || child.isRemoved()) return null;

        UUID assemblyId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (assemblyId == null) return null;

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
        if (assembly == null) {
            return new Selection(child, null, null, null);
        }

        // While Create owns the Frames, their persisted source positions are only historical geometry.
        // The stationary Create controller is the physical point that still belongs to the host and is
        // therefore the correct authority/pivot for Physics Staff selection.
        PendingContraptionMove move = manager.pendingContraptionMove(assemblyId).orElse(null);
        if (move != null) {
            BlockPos controller = move.controllerPosition().orElse(null);
            if (controller == null) {
                return new Selection(child, null, assembly, null);
            }
            MechanismAssemblyHost.Resolution resolution = MechanismAssemblyHost.resolve(level, controller);
            ServerSubLevel host = resolution.kind() == MechanismAssemblyHost.Kind.FOREIGN
                    ? resolution.subLevel()
                    : null;
            if (host == null || host.isRemoved()) {
                return new Selection(child, null, assembly, controller);
            }
            return new Selection(child, host, assembly, controller);
        }

        HostedMiniPhysicalAttachment.Attachment attachment =
                HostedMiniPhysicalAttachment.resolve(level, assembly, child);
        if (attachment == null) {
            return new Selection(child, null, assembly, null);
        }

        return new Selection(
                child,
                attachment.physicalBody(),
                attachment.assembly(),
                null);
    }

    public record Selection(
            ServerSubLevel child,
            @Nullable ServerSubLevel host,
            @Nullable MechanismAssembly assembly,
            @Nullable BlockPos controllerPivot) {

        public boolean hasHost() {
            return host != null && assembly != null;
        }

        /**
         * Returns the physical host-local drag pivot. Static Frames use the selected mini cell's owning
         * Frame; a Frame carried by Create uses the stationary block that manages the contraption.
         */
        public Vector3d framePivot(Vector3dc childLocalAnchor) {
            if (!hasHost()) {
                throw new IllegalStateException("Managed selection has no physical host");
            }
            if (controllerPivot != null) {
                return new Vector3d(
                        controllerPivot.getX() + 0.5,
                        controllerPivot.getY() + 0.5,
                        controllerPivot.getZ() + 0.5);
            }
            BlockPos childBlock = BlockPos.containing(
                    childLocalAnchor.x(),
                    childLocalAnchor.y(),
                    childLocalAnchor.z());
            BlockPos mini = childBlock.subtract(child.getPlot().getCenterBlock());
            BlockPos frame = MiniCoordinateMapper.miniToFrame(assembly, mini);
            return new Vector3d(
                    frame.getX() + 0.5,
                    frame.getY() + 0.5,
                    frame.getZ() + 0.5);
        }
    }
}
