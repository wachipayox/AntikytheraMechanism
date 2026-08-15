package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Optional Simulated integration for a Physics Assembler placed as a real Antikythera mini block. */
@Mixin(value = PhysicsAssemblerBlockEntity.class, remap = false)
abstract class PhysicsAssemblerMiniPhysicsMixin {

    /**
     * A Frame child is a static container, not an already-assembled Simulated body. Present it as
     * unassembled to Simulated's lever/tooltip initialization. Detached Antikythera physics bodies
     * intentionally keep the ordinary "assembled" visual state.
     */
    @WrapMethod(method = "getSubLevel")
    private @Nullable SubLevel antikytheramechanism$frameChildLooksUnassembled(
            Operation<SubLevel> original) {
        SubLevel actual = original.call();
        return actual != null
                && MiniWorldEnvironment.isManagedSubLevel(actual)
                && DetachedMiniPhysicsSubLevelService.hasHalfScale(actual)
                ? null
                : actual;
    }

    /** Detached mini bodies are one-way. There is deliberately no Frame-finding disassembly path. */
    @Inject(method = "assembleOrDisassemble", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$rejectDetachedDisassembly(CallbackInfo ci) {
        PhysicsAssemblerBlockEntity self = (PhysicsAssemblerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            ci.cancel();
            return;
        }
        SubLevel actual = Sable.HELPER.getContaining(self);
        if (DetachedMiniPhysicsSubLevelService.isDetached(actual)) {
            ci.cancel();
            return;
        }
        if (actual != null && MiniWorldEnvironment.isManagedSubLevel(actual)) {
            if (!(level instanceof ServerLevel serverLevel)
                    || MiniPhysicsAssemblyContext.validFrameSource(serverLevel, self.getBlockPos()) == null) {
                // Never let Simulated reinterpret a locked/moving/invalid Frame child as a body to
                // disassemble. The operation can be retried once the Frame is stably docked again.
                ci.cancel();
            }
        }
    }

    /**
     * Simulated branches on Sable#getContaining directly instead of its private getSubLevel helper.
     * Sable.HELPER is declared as ActiveSableCompanion, so the JVM call-site owner is that concrete
     * runtime type even though getContaining is conceptually part of the companion API. Hide only a
     * valid Frame child at this decision point; the actual assembly still sees the real source world.
     */
    @WrapOperation(
            method = "assembleOrDisassemble",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContaining(Lnet/minecraft/world/level/block/entity/BlockEntity;)Ldev/ryanhcode/sable/sublevel/SubLevel;"))
    private SubLevel antikytheramechanism$treatFrameChildAsAssemblySource(
            ActiveSableCompanion companion,
            BlockEntity blockEntity,
            Operation<SubLevel> original) {
        SubLevel actual = original.call(companion, blockEntity);
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)
                || MiniPhysicsAssemblyContext.validFrameSource(serverLevel, blockEntity.getBlockPos()) == null) {
            return actual;
        }
        return null;
    }

    /**
     * Keep Simulated's native BFS/glue/chassis assembly implementation, but constrain its candidates
     * to the exact source mini world and convert the resulting Sable body into the detached subtype.
     */
    @WrapOperation(
            method = "assembleOrDisassemble",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/util/SimAssemblyHelper;assembleFromSingleBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;ZZ)Ldev/simulated_team/simulated/util/SimAssemblyHelper$AssemblyResult;"))
    private SimAssemblyHelper.AssemblyResult antikytheramechanism$assembleDetachedMiniBody(
            Level level,
            BlockPos selfPos,
            BlockPos toAssemble,
            boolean includeStart,
            boolean includeEncasingGlue,
            Operation<SimAssemblyHelper.AssemblyResult> original) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return original.call(level, selfPos, toAssemble, includeStart, includeEncasingGlue);
        }
        ServerSubLevel source = MiniPhysicsAssemblyContext.validFrameSource(serverLevel, selfPos);
        if (source == null) {
            return original.call(level, selfPos, toAssemble, includeStart, includeEncasingGlue);
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(source);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = ownerId == null ? null : manager.getAssembly(ownerId).orElse(null);
        if (assembly == null || !MiniPhysicsAssemblyContext.begin(serverLevel, source)) {
            return null;
        }

        MotionInheritance motion = MotionInheritance.capture(serverLevel, assembly, source);
        SimAssemblyHelper.AssemblyResult result;
        try {
            result = original.call(level, selfPos, toAssemble, includeStart, includeEncasingGlue);
        } finally {
            MiniPhysicsAssemblyContext.end();
        }

        if (result == null) {
            return null;
        }
        if (!(result.subLevel() instanceof ServerSubLevel detached)
                || detached == source
                || detached.isRemoved()) {
            AntikytheraMechanism.LOGGER.error(
                    "Simulated mini assembly for Frame assembly {} did not produce a distinct live ServerSubLevel",
                    assembly.id());
            return result;
        }

        DetachedMiniPhysicsSubLevelService.markDetached(detached);
        motion.applyTo(detached);

        // Sable's move callbacks update actual mini contents. Refresh only Frame presentation/state;
        // ownership of the new rigid body is intentionally not added to MechanismAssemblyManager.
        for (BlockPos frame : assembly.frames()) {
            if (serverLevel.hasChunkAt(frame)) {
                manager.refreshFrame(serverLevel, frame);
            }
        }
        LazySubLevelLifecycle.requestRetirementCheck(serverLevel, assembly.id());
        return result;
    }

    /** Transfers the motion of a foreign Sable host instead of the pose-driven Frame child's zeroed velocity. */
    private record MotionInheritance(
            Vector3d sourcePosition,
            Vector3d sourceLinear,
            Vector3d sourceAngular,
            @Nullable Vector3d hostPosition,
            @Nullable Vector3d hostLinear,
            @Nullable Vector3d hostAngular) {

        private static MotionInheritance capture(
                ServerLevel level,
                MechanismAssembly assembly,
                ServerSubLevel source) {
            RigidBodyHandle sourceHandle = RigidBodyHandle.of(source);
            boolean sourceMotionAvailable = sourceHandle != null && sourceHandle.isValid();
            Vector3d sourceLinear = sourceMotionAvailable
                    ? sourceHandle.getLinearVelocity(new Vector3d())
                    : new Vector3d();
            Vector3d sourceAngular = sourceMotionAvailable
                    ? sourceHandle.getAngularVelocity(new Vector3d())
                    : new Vector3d();
            Vector3d sourcePosition = new Vector3d(source.logicalPose().position());

            MechanismAssemblyHost.Resolution resolution = MechanismAssemblyHost.resolve(level, assembly.origin());
            if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN || resolution.subLevel() == null) {
                return new MotionInheritance(
                        sourcePosition, sourceLinear, sourceAngular, null, null, null);
            }

            ServerSubLevel host = resolution.subLevel();
            RigidBodyHandle hostHandle = RigidBodyHandle.of(host);
            if (hostHandle == null || !hostHandle.isValid()) {
                AntikytheraMechanism.LOGGER.warn(
                        "Could not resolve foreign host velocity while ejecting mini physics body from assembly {}",
                        assembly.id());
                return new MotionInheritance(
                        sourcePosition, sourceLinear, sourceAngular, null, null, null);
            }
            return new MotionInheritance(
                    sourcePosition,
                    sourceLinear,
                    sourceAngular,
                    new Vector3d(host.logicalPose().position()),
                    hostHandle.getLinearVelocity(new Vector3d()),
                    hostHandle.getAngularVelocity(new Vector3d()));
        }

        private void applyTo(ServerSubLevel detached) {
            if (hostPosition == null || hostLinear == null || hostAngular == null) {
                return;
            }
            RigidBodyHandle detachedHandle = RigidBodyHandle.of(detached);
            if (detachedHandle == null || !detachedHandle.isValid()) {
                AntikytheraMechanism.LOGGER.debug(
                        "Detached mini physics body {} has no rigid-body handle yet; Sable will retain its native inherited motion",
                        detached.getUniqueId());
                return;
            }

            Vector3d bodyPosition = new Vector3d(detached.logicalPose().position());
            Vector3d inheritedPointVelocity = new Vector3d(sourceAngular)
                    .cross(new Vector3d(bodyPosition).sub(sourcePosition))
                    .add(sourceLinear);
            Vector3d desiredPointVelocity = new Vector3d(hostAngular)
                    .cross(new Vector3d(bodyPosition).sub(hostPosition))
                    .add(hostLinear);

            // Sable already inherited the source child's motion during assembleBlocks. Apply only
            // the delta required to make the released body inherit the real foreign host motion.
            detachedHandle.addLinearAndAngularVelocity(
                    desiredPointVelocity.sub(inheritedPointVelocity),
                    new Vector3d(hostAngular).sub(sourceAngular));
        }
    }
}
