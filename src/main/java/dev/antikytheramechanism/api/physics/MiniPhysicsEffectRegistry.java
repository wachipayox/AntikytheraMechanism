package dev.antikytheramechanism.api.physics;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class MiniPhysicsEffectRegistry {
    public static final double MINI_VOLUME_SCALE = 1.0 / 8.0;
    private static final Map<ResourceLocation, TransferPolicy> POLICIES = new ConcurrentHashMap<>();

    private MiniPhysicsEffectRegistry() {}

    @FunctionalInterface
    public interface TransferPolicy {
        void transfer(TransferContext context, Vector3dc linearImpulse, Vector3dc angularImpulse);
    }

    public static void register(ResourceLocation effectId, TransferPolicy policy) {
        Objects.requireNonNull(effectId);
        Objects.requireNonNull(policy);
        if (POLICIES.putIfAbsent(effectId, policy) != null) {
            throw new IllegalStateException("Mini physics effect already registered: " + effectId);
        }
    }

    public static void registerVolumeScaled(ResourceLocation effectId) {
        register(effectId, (context, linearImpulse, angularImpulse) ->
                context.applySameEffect(linearImpulse, angularImpulse, MINI_VOLUME_SCALE));
    }

    public static boolean transfer(
            ResourceLocation effectId,
            ServerSubLevel managedChild,
            Vector3dc linearImpulse,
            Vector3dc angularImpulse) {
        TransferPolicy policy = POLICIES.get(effectId);
        if (policy == null || managedChild == null || managedChild.isRemoved()) return false;

        ServerLevel level = managedChild.getLevel();
        java.util.UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(managedChild);
        if (ownerId == null) return false;
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null || !managedChild.getUniqueId().equals(assembly.subLevelId())) return false;

        MechanismAssemblyHost.Resolution resolution = MechanismAssemblyHost.resolve(level, assembly.origin());
        if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || resolution.subLevel() == null
                || resolution.subLevel().isRemoved()) return false;

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        if (physicsSystem == null) return false;
        policy.transfer(
                new TransferContext(level, managedChild, resolution.subLevel(), assembly, physicsSystem),
                linearImpulse,
                angularImpulse);
        return true;
    }

    public record TransferContext(
            ServerLevel level,
            ServerSubLevel child,
            ServerSubLevel host,
            MechanismAssembly assembly,
            SubLevelPhysicsSystem physicsSystem) {

        public void applySameEffect(Vector3dc childLinear, Vector3dc childAngular, double scale) {
            if (!Double.isFinite(scale) || scale == 0.0) return;

            Quaterniond childToHost = new Quaterniond(host.logicalPose().orientation())
                    .conjugate()
                    .mul(child.logicalPose().orientation())
                    .normalize();
            Vector3d hostLinear = childToHost.transform(childLinear, new Vector3d()).mul(scale);
            Vector3d hostAngular = childToHost.transform(childAngular, new Vector3d()).mul(scale);

            Vector3d childCenterWorld = child.logicalPose().transformPosition(
                    new Vector3d(child.getMassTracker().getCenterOfMass()));
            Vector3d childCenterInHost = host.logicalPose().transformPositionInverse(childCenterWorld);
            Vector3d lever = childCenterInHost.sub(host.getMassTracker().getCenterOfMass(), new Vector3d());
            hostAngular.add(lever.cross(hostLinear, new Vector3d()));

            physicsSystem.getPhysicsHandle(host)
                    .applyLinearAndAngularImpulse(hostLinear, hostAngular, true);
        }
    }
}
