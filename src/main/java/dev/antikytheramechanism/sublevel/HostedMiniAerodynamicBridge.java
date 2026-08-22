package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Projects lift/drag from a managed 0.5-scale mini provider onto the rigid body carrying its Frame.
 *
 * <p>The provider's own Sable implementation remains authoritative. We express the mini block as a
 * fractional provider in host-local coordinates, let Sable calculate pressure, point velocity, drag,
 * lift and torque, then scale both resulting impulses by physical surface area (0.5^2 = 0.25). The
 * pose-driven managed child never receives the aerodynamic impulse itself.</p>
 */
public final class HostedMiniAerodynamicBridge {
    public static final double MINI_SURFACE_SCALE = 0.25;

    private HostedMiniAerodynamicBridge() {
    }

    /**
     * @return true when {@code logicalBody} is Antikythera-managed. A true result means the caller
     * must suppress Sable's ordinary application to the managed child even when no foreign rigid host
     * currently exists (root-world Frames have no Sable rigid body to accelerate).
     */
    public static boolean project(
            BlockSubLevelLiftProvider provider,
            BlockSubLevelLiftProvider.LiftProviderContext context,
            ServerSubLevel logicalBody,
            double timeStep) {
        if (!(logicalBody.getLevel() instanceof ServerLevel)
                || MechanismSubLevelService.getOwnerAssemblyId(logicalBody) == null) {
            return false;
        }

        Contribution contribution = calculateHosted(provider, context, logicalBody, timeStep);
        if (contribution == null) {
            return true;
        }
        RigidBodyHandle physicalHandle = RigidBodyHandle.of(contribution.physicalBody());
        if (physicalHandle != null && physicalHandle.isValid()) {
            physicalHandle.applyLinearAndAngularImpulse(
                    contribution.linearImpulse(), contribution.angularImpulse(), false);
        }
        return true;
    }

    /**
     * Calculates one foreign-host contribution using Sable's native provider implementation. This is
     * intentionally side-effect free so runtime tests and force diagnostics can verify the exact
     * scaled impulse before it is handed to the physics pipeline.
     */
    public static @Nullable Contribution calculateHosted(
            BlockSubLevelLiftProvider provider,
            BlockSubLevelLiftProvider.LiftProviderContext context,
            ServerSubLevel logicalBody,
            double timeStep) {
        if (!(logicalBody.getLevel() instanceof ServerLevel level)
                || MechanismSubLevelService.getOwnerAssemblyId(logicalBody) == null) {
            return null;
        }

        HostedMiniPhysicalAttachment.Attachment attachment =
                HostedMiniPhysicalAttachment.resolve(level, logicalBody);
        if (attachment == null) {
            return null;
        }

        ServerSubLevel physicalBody = attachment.physicalBody();
        RigidBodyHandle physicalHandle = RigidBodyHandle.of(physicalBody);
        if (physicalHandle == null || !physicalHandle.isValid()) {
            return null;
        }

        Vector3d logicalCenter = new Vector3d(
                context.pos().getX() + 0.5,
                context.pos().getY() + 0.5,
                context.pos().getZ() + 0.5);
        Vector3d physicalCenter = attachment.logicalToPhysical(logicalCenter, new Vector3d());
        Vector3d physicalNormal = attachment.logicalVectorToPhysical(
                new Vector3d(context.dir().x, context.dir().y, context.dir().z),
                new Vector3d());
        if (physicalNormal.lengthSquared() < 1.0E-20) {
            return null;
        }
        physicalNormal.normalize();

        BlockSubLevelLiftProvider.LiftProviderContext projectedContext =
                new BlockSubLevelLiftProvider.LiftProviderContext(
                        BlockPos.ZERO,
                        context.state(),
                        new Vec3(physicalNormal.x, physicalNormal.y, physicalNormal.z));

        Pose3d fractionalPose = new Pose3d();
        fractionalPose.position().set(physicalCenter).sub(0.5, 0.5, 0.5);
        fractionalPose.orientation().identity();
        fractionalPose.scale().set(1.0, 1.0, 1.0);

        Vector3d linearVelocity = physicalHandle.getLinearVelocity(new Vector3d());
        Vector3d angularVelocity = physicalHandle.getAngularVelocity(new Vector3d());
        Vector3d linearImpulse = new Vector3d();
        Vector3d angularImpulse = new Vector3d();

        provider.sable$contributeLiftAndDrag(
                projectedContext,
                physicalBody,
                fractionalPose,
                timeStep,
                linearVelocity,
                angularVelocity,
                linearImpulse,
                angularImpulse,
                null);

        linearImpulse.mul(MINI_SURFACE_SCALE);
        angularImpulse.mul(MINI_SURFACE_SCALE);
        return new Contribution(
                physicalBody,
                new Vector3d(physicalCenter),
                new Vector3d(physicalNormal),
                linearImpulse,
                angularImpulse);
    }

    public record Contribution(
            ServerSubLevel physicalBody,
            Vector3d physicalCenter,
            Vector3d physicalNormal,
            Vector3d linearImpulse,
            Vector3d angularImpulse) {
    }
}
