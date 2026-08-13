package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyOrientationMath;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
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

public final class MechanismAssemblyHost {
    private static final double SCALE_EPSILON = 1.0E-6;
    private MechanismAssemblyHost() {}

    public enum Kind { ROOT, FOREIGN, MANAGED_ANTIKYTHERA, UNSUPPORTED_SCALE }

    public record Resolution(Kind kind, @Nullable ServerSubLevel subLevel) {
        public boolean allowed() { return kind == Kind.ROOT || kind == Kind.FOREIGN; }
        public @Nullable UUID foreignId() {
            return kind == Kind.FOREIGN && subLevel != null ? subLevel.getUniqueId() : null;
        }
    }

    public static Resolution resolve(ServerLevel level, BlockPos position) {
        SubLevel containing = Sable.HELPER.getContaining(level, position);
        if (containing == null) return new Resolution(Kind.ROOT, null);
        if (!(containing instanceof ServerSubLevel serverSubLevel)) return new Resolution(Kind.UNSUPPORTED_SCALE, null);
        if (MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null)
            return new Resolution(Kind.MANAGED_ANTIKYTHERA, serverSubLevel);
        if (!hasUnitScale(serverSubLevel)) return new Resolution(Kind.UNSUPPORTED_SCALE, serverSubLevel);
        return new Resolution(Kind.FOREIGN, serverSubLevel);
    }

    public static boolean canHostFrame(Level level, BlockPos position) {
        SubLevel containing = Sable.HELPER.getContaining(level, position);
        return containing == null || !isManaged(containing) && hasUnitScale(containing);
    }

    public static boolean canHostFrame(ServerLevel level, BlockPos position) { return resolve(level, position).allowed(); }

    public static boolean samePhysicalHost(Level level, BlockPos first, BlockPos second) {
        SubLevel firstHost = Sable.HELPER.getContaining(level, first);
        SubLevel secondHost = Sable.HELPER.getContaining(level, second);
        if (isManaged(firstHost) || isManaged(secondHost)) return false;
        if (firstHost == null || secondHost == null) return firstHost == null && secondHost == null;
        return firstHost.getUniqueId().equals(secondHost.getUniqueId());
    }

    public static boolean samePhysicalHost(ServerLevel level, MechanismAssembly assembly, BlockPos position) {
        return samePhysicalHost(level, assembly.origin(), position);
    }

    public static @Nullable AssemblyPose worldPose(ServerLevel level, MechanismAssembly assembly) {
        Resolution host = resolve(level, assembly.origin());
        if (host.kind() == Kind.ROOT) return assembly.poseTarget();
        if (host.kind() != Kind.FOREIGN || host.subLevel() == null) return null;
        ServerSubLevel foreign = host.subLevel();
        Vector3d localAnchor = assembly.poseTarget().anchor(new Vector3d());
        Vector3d worldAnchor = foreign.logicalPose().transformPosition(localAnchor, new Vector3d());
        Quaterniond worldOrientation = new Quaterniond(foreign.logicalPose().orientation()).normalize()
                .mul(assembly.poseTarget().orientation(new Quaterniond())).normalize();
        return AssemblyPose.of(worldAnchor, worldOrientation);
    }

    /** A docked boundary may be yaw-rotated physically while the mini plot stays in logical axes. */
    public static boolean boundaryIsAligned(ServerLevel level, MechanismAssembly assembly, double epsilon) {
        Resolution host = resolve(level, assembly.origin());
        return host.allowed() && AssemblyOrientationMath.isDocked(assembly, epsilon);
    }

    public static boolean sameResolvedHost(ServerLevel level, BlockPos first, BlockPos second) {
        Resolution a = resolve(level, first), b = resolve(level, second);
        if (!a.allowed() || !b.allowed() || a.kind() != b.kind()) return false;
        if (a.kind() == Kind.ROOT) return true;
        return a.foreignId() != null && a.foreignId().equals(b.foreignId());
    }

    private static boolean hasUnitScale(SubLevel host) {
        return Math.abs(host.logicalPose().scale().x() - 1.0) <= SCALE_EPSILON
                && Math.abs(host.logicalPose().scale().y() - 1.0) <= SCALE_EPSILON
                && Math.abs(host.logicalPose().scale().z() - 1.0) <= SCALE_EPSILON;
    }

    private static boolean isManaged(@Nullable SubLevel subLevel) {
        if (subLevel == null) return false;
        if (subLevel instanceof ServerSubLevel serverSubLevel)
            return MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel) != null;
        return MiniWorldEnvironment.isManagedSubLevel(subLevel);
    }
}
