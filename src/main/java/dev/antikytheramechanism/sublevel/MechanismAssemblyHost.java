package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Resolves the physical space that contains a Mechanism Frame.
 *
 * <p>Frames in the root level are root-hosted. Frames stored in a foreign Sable plot are hosted by
 * that SubLevel and their managed 1:2 content world follows the host pose. A managed Antikythera
 * SubLevel is never a valid host: that would be a Frame inside a Frame.</p>
 *
 * <p>The host is intentionally derived from the Frame's physical plot position instead of persisted
 * as duplicate metadata. Sable plot coordinates are stable while a foreign body moves, so this also
 * makes save/reload and root <-> foreign-host adoption self-reconciling.</p>
 */
public final class MechanismAssemblyHost {
    private static final double SCALE_EPSILON = 1.0E-6;

    private MechanismAssemblyHost() {
    }

    public enum Kind {
        ROOT,
        FOREIGN,
        MANAGED_ANTIKYTHERA,
        UNSUPPORTED_SCALE
    }

    public record Resolution(Kind kind, @Nullable ServerSubLevel subLevel) {
        public boolean allowed() {
            return kind == Kind.ROOT || kind == Kind.FOREIGN;
        }

        public @Nullable UUID foreignId() {
            return kind == Kind.FOREIGN && subLevel != null ? subLevel.getUniqueId() : null;
        }
    }

    /** Classifies the Sable space containing one physical Frame/storage position. */
    public static Resolution resolve(ServerLevel level, BlockPos position) {
        SubLevel containing = Sable.HELPER.getContaining(level, position);
        if (containing == null) {
            return new Resolution(Kind.ROOT, null);
        }
        if (!(containing instanceof ServerSubLevel serverSubLevel)) {
            return new Resolution(Kind.UNSUPPORTED_SCALE, null);
        }
        if (MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null) {
            return new Resolution(Kind.MANAGED_ANTIKYTHERA, serverSubLevel);
        }
        if (!hasUnitScale(serverSubLevel)) {
            return new Resolution(Kind.UNSUPPORTED_SCALE, serverSubLevel);
        }
        return new Resolution(Kind.FOREIGN, serverSubLevel);
    }

    public static boolean canHostFrame(ServerLevel level, BlockPos position) {
        return resolve(level, position).allowed();
    }

    /**
     * Returns true when two storage positions belong to the same usable physical space. This helper
     * also works client-side for placement routing; managed Antikythera worlds are rejected by their
     * synchronized name marker there and by owner metadata on the server.
     */
    public static boolean samePhysicalHost(Level level, BlockPos first, BlockPos second) {
        SubLevel firstHost = Sable.HELPER.getContaining(level, first);
        SubLevel secondHost = Sable.HELPER.getContaining(level, second);
        if (isManaged(firstHost) || isManaged(secondHost)) {
            return false;
        }
        if (firstHost == null || secondHost == null) {
            return firstHost == null && secondHost == null;
        }
        return firstHost.getUniqueId().equals(secondHost.getUniqueId());
    }

    /** True when the position lives in the same root/foreign host as the assembly origin. */
    public static boolean samePhysicalHost(ServerLevel level, MechanismAssembly assembly, BlockPos position) {
        return samePhysicalHost(level, assembly.origin(), position);
    }

    /**
     * Recomputes the current world-space pose of a foreign-hosted assembly. Root-hosted assemblies
     * retain their own pose target because pistons/Create may be animating them independently.
     */
    public static @Nullable AssemblyPose currentHostedPose(ServerLevel level, MechanismAssembly assembly) {
        Resolution host = resolve(level, assembly.origin());
        if (host.kind() != Kind.FOREIGN || host.subLevel() == null) {
            return null;
        }
        return poseFromHost(host.subLevel(), assembly.origin());
    }

    /**
     * Synchronizes only foreign-hosted pose targets. This is transient derived state and deliberately
     * does not dirty SavedData every physics/server tick.
     */
    public static boolean synchronizePose(ServerLevel level, MechanismAssembly assembly) {
        AssemblyPose hosted = currentHostedPose(level, assembly);
        if (hosted == null) {
            return false;
        }
        assembly.setPoseTarget(hosted);
        return true;
    }

    public static void synchronizeAll(ServerLevel level, MechanismAssemblyManager manager) {
        for (MechanismAssembly assembly : manager.assemblies()) {
            synchronizePose(level, assembly);
        }
    }

    /**
     * Boundary bridges are valid only while the managed child shares the host's local axes. Root
     * assemblies preserve the old world-aligned requirement; foreign assemblies compare against the
     * host-derived world pose instead.
     */
    public static boolean boundaryIsAligned(
            ServerLevel level,
            MechanismAssembly assembly,
            double epsilon) {
        Resolution host = resolve(level, assembly.origin());
        if (host.kind() == Kind.ROOT) {
            return assembly.poseTarget().approximatelyEquals(AssemblyPose.identityAt(assembly.origin()), epsilon);
        }
        if (host.kind() != Kind.FOREIGN || host.subLevel() == null) {
            return false;
        }
        return assembly.poseTarget().approximatelyEquals(
                poseFromHost(host.subLevel(), assembly.origin()), epsilon);
    }

    /** Returns true when two resolved server-side positions have exactly the same usable host. */
    public static boolean sameResolvedHost(ServerLevel level, BlockPos first, BlockPos second) {
        Resolution a = resolve(level, first);
        Resolution b = resolve(level, second);
        if (!a.allowed() || !b.allowed() || a.kind() != b.kind()) {
            return false;
        }
        if (a.kind() == Kind.ROOT) {
            return true;
        }
        return a.foreignId() != null && a.foreignId().equals(b.foreignId());
    }

    private static AssemblyPose poseFromHost(ServerSubLevel host, BlockPos frameOrigin) {
        Vector3d worldAnchor = host.logicalPose().transformPosition(
                new Vector3d(
                        frameOrigin.getX() + 0.5,
                        frameOrigin.getY() + 0.5,
                        frameOrigin.getZ() + 0.5),
                new Vector3d());
        Quaterniond orientation = new Quaterniond(host.logicalPose().orientation()).normalize();
        return AssemblyPose.of(worldAnchor, orientation);
    }

    private static boolean hasUnitScale(ServerSubLevel host) {
        return Math.abs(host.logicalPose().scale().x() - 1.0) <= SCALE_EPSILON
                && Math.abs(host.logicalPose().scale().y() - 1.0) <= SCALE_EPSILON
                && Math.abs(host.logicalPose().scale().z() - 1.0) <= SCALE_EPSILON;
    }

    private static boolean isManaged(@Nullable SubLevel subLevel) {
        if (subLevel == null) {
            return false;
        }
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            return MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null;
        }
        return MiniWorldEnvironment.isManagedSubLevel(subLevel);
    }
}
