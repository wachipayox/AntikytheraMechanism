package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.sublevel.HostedMiniForceProjection;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes a Simulated spring attached to a real mini block react against the foreign Sable body
 * carrying that mini block's Mechanism Frame.
 *
 * <p>Simulated correctly computes spring geometry in the managed child's visual coordinate space,
 * but normally sends damping/impulses to that child's rigid body. Antikythera pose-drives and
 * zeroes that body every physics step, so the reaction disappears. This mixin keeps Simulated's
 * native Hooke/snap/damping algorithm and only substitutes the physical endpoint at its primitive
 * physics operations.</p>
 */
@Mixin(value = SpringBlockEntity.class, remap = false)
abstract class SpringBlockEntityHostedMiniMixin {
    @Shadow
    private ForceTotal forceTotal;

    @Shadow
    private ForceTotal partnerForceTotal;

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getVelocity(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private Vector3d antikytheramechanism$usePhysicalHostPointVelocity(
            ActiveSableCompanion companion,
            Level level,
            Vector3dc plotPosition,
            Vector3d dest,
            Operation<Vector3d> original) {
        if (level instanceof ServerLevel serverLevel) {
            HostedMiniForceProjection.Projection projection =
                    HostedMiniForceProjection.projectContaining(serverLevel, plotPosition);
            if (projection != null) {
                return original.call(
                        companion,
                        level,
                        projection.physicalPlotPosition(),
                        dest);
            }
        }
        return original.call(companion, level, plotPosition, dest);
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;applyLocalDamping(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;Ldev/ryanhcode/sable/api/physics/force/ForceTotal;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;D)V"))
    private void antikytheramechanism$applyDampingToPhysicalHost(
            SpringBlockEntity spring,
            ServerSubLevel logicalBody,
            RigidBodyHandle logicalHandle,
            ForceTotal total,
            Vector3dc logicalPoint,
            Vector3dc dampingPointForce,
            Vector3dc dampingTorque,
            double timeStep,
            Operation<Void> original) {
        HostedMiniForceProjection.Projection projection =
                HostedMiniForceProjection.project(
                        logicalBody.getLevel(),
                        logicalBody,
                        logicalPoint);
        if (projection != null) {
            RigidBodyHandle physicalHandle =
                    RigidBodyHandle.of(projection.physicalBody());
            if (physicalHandle != null && physicalHandle.isValid()) {
                original.call(
                        spring,
                        projection.physicalBody(),
                        physicalHandle,
                        total,
                        projection.physicalPlotPosition(),
                        dampingPointForce,
                        dampingTorque,
                        timeStep);
                return;
            }
        }

        original.call(
                spring,
                logicalBody,
                logicalHandle,
                total,
                logicalPoint,
                dampingPointForce,
                dampingTorque,
                timeStep);
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"))
    private void antikytheramechanism$applyPointImpulseToPhysicalHost(
            ForceTotal total,
            ServerSubLevel logicalBody,
            Vector3dc logicalPoint,
            Vector3dc logicalImpulse,
            Operation<Void> original) {
        HostedMiniForceProjection.Projection projection =
                HostedMiniForceProjection.project(
                        logicalBody.getLevel(),
                        logicalBody,
                        logicalPoint);
        if (projection == null) {
            original.call(total, logicalBody, logicalPoint, logicalImpulse);
            return;
        }

        Vector3d physicalImpulse = HostedMiniForceProjection.transformLocalVector(
                logicalBody,
                projection.physicalBody(),
                logicalImpulse);
        original.call(
                total,
                projection.physicalBody(),
                projection.physicalPlotPosition(),
                physicalImpulse);
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyLinearAndAngularImpulse(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"))
    private void antikytheramechanism$expressFreeImpulseInPhysicalHost(
            ForceTotal total,
            Vector3dc logicalLinearImpulse,
            Vector3dc logicalAngularImpulse,
            Operation<Void> original) {
        ServerSubLevel logicalBody = antikytheramechanism$logicalBodyFor(total);
        if (logicalBody == null) {
            original.call(total, logicalLinearImpulse, logicalAngularImpulse);
            return;
        }

        ServerSubLevel physicalBody = HostedMiniForceProjection.foreignHost(
                logicalBody.getLevel(),
                logicalBody);
        if (physicalBody == null) {
            original.call(total, logicalLinearImpulse, logicalAngularImpulse);
            return;
        }

        original.call(
                total,
                HostedMiniForceProjection.transformLocalVector(
                        logicalBody, physicalBody, logicalLinearImpulse),
                HostedMiniForceProjection.transformLocalVector(
                        logicalBody, physicalBody, logicalAngularImpulse));
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;getAngularVelocity(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
                    ordinal = 0))
    private Vector3d antikytheramechanism$readPrimaryHostAngularVelocity(
            RigidBodyHandle logicalHandle,
            Vector3d dest,
            Operation<Vector3d> original) {
        return antikytheramechanism$readPhysicalAngularVelocity(
                antikytheramechanism$primaryLogicalBody(),
                logicalHandle,
                dest,
                original);
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;getAngularVelocity(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
                    ordinal = 1))
    private Vector3d antikytheramechanism$readPartnerHostAngularVelocity(
            RigidBodyHandle logicalHandle,
            Vector3d dest,
            Operation<Vector3d> original) {
        return antikytheramechanism$readPhysicalAngularVelocity(
                antikytheramechanism$partnerLogicalBody(),
                logicalHandle,
                dest,
                original);
    }

    @WrapOperation(
            method = "sable$physicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;applyForcesAndReset(Ldev/ryanhcode/sable/api/physics/force/ForceTotal;)V"))
    private void antikytheramechanism$applySpringTotalToPhysicalHost(
            RigidBodyHandle logicalHandle,
            ForceTotal total,
            Operation<Void> original) {
        ServerSubLevel logicalBody = antikytheramechanism$logicalBodyFor(total);
        if (logicalBody != null) {
            ServerSubLevel physicalBody = HostedMiniForceProjection.foreignHost(
                    logicalBody.getLevel(),
                    logicalBody);
            if (physicalBody != null) {
                RigidBodyHandle physicalHandle = RigidBodyHandle.of(physicalBody);
                if (physicalHandle != null && physicalHandle.isValid()) {
                    original.call(physicalHandle, total);
                    return;
                }
            }
        }
        original.call(logicalHandle, total);
    }

    private Vector3d antikytheramechanism$readPhysicalAngularVelocity(
            @Nullable ServerSubLevel logicalBody,
            RigidBodyHandle logicalHandle,
            Vector3d dest,
            Operation<Vector3d> original) {
        if (logicalBody != null) {
            ServerSubLevel physicalBody = HostedMiniForceProjection.foreignHost(
                    logicalBody.getLevel(),
                    logicalBody);
            if (physicalBody != null) {
                RigidBodyHandle physicalHandle = RigidBodyHandle.of(physicalBody);
                if (physicalHandle != null && physicalHandle.isValid()) {
                    return original.call(physicalHandle, dest);
                }
            }
        }
        return original.call(logicalHandle, dest);
    }

    private @Nullable ServerSubLevel antikytheramechanism$logicalBodyFor(
            ForceTotal total) {
        if (total == this.forceTotal) {
            return antikytheramechanism$primaryLogicalBody();
        }
        if (total == this.partnerForceTotal) {
            return antikytheramechanism$partnerLogicalBody();
        }
        return null;
    }

    private @Nullable ServerSubLevel antikytheramechanism$primaryLogicalBody() {
        SpringBlockEntity self = (SpringBlockEntity) (Object) this;
        return antikytheramechanism$containingServerSubLevel(self);
    }

    private @Nullable ServerSubLevel antikytheramechanism$partnerLogicalBody() {
        SpringBlockEntity self = (SpringBlockEntity) (Object) this;
        return antikytheramechanism$containingServerSubLevel(self.getPairedSpring());
    }

    private static @Nullable ServerSubLevel antikytheramechanism$containingServerSubLevel(
            @Nullable SpringBlockEntity spring) {
        if (spring == null) {
            return null;
        }
        SubLevel containing = Sable.HELPER.getContaining(spring);
        return containing instanceof ServerSubLevel serverSubLevel
                ? serverSubLevel
                : null;
    }
}
