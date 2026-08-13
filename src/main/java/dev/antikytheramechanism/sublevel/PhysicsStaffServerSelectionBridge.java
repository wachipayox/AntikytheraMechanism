package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
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
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(assemblyId).orElse(null);
        if (assembly == null || !selectedId.equals(assembly.subLevelId())) {
            return new Selection(child, null, assembly);
        }

        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
        if (host.kind() != MechanismAssemblyHost.Kind.FOREIGN || host.subLevel() == null || host.subLevel().isRemoved()) {
            return new Selection(child, null, assembly);
        }
        return new Selection(child, host.subLevel(), assembly);
    }

    public record Selection(
            ServerSubLevel child,
            @Nullable ServerSubLevel host,
            @Nullable MechanismAssembly assembly) {

        public boolean hasHost() {
            return host != null && assembly != null;
        }

        /** Converts a child plot-space staff anchor to the center of its owning physical Frame. */
        public Vector3d framePivot(Vector3dc childLocalAnchor) {
            if (!hasHost()) throw new IllegalStateException("Managed selection has no physical host");
            BlockPos childBlock = BlockPos.containing(
                    childLocalAnchor.x(), childLocalAnchor.y(), childLocalAnchor.z());
            BlockPos mini = childBlock.subtract(child.getPlot().getCenterBlock());
            BlockPos frame = MiniCoordinateMapper.miniToFrame(assembly, mini);
            return new Vector3d(frame.getX() + 0.5, frame.getY() + 0.5, frame.getZ() + 0.5);
        }
    }
}
