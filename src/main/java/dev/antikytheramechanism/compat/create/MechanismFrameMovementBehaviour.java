package dev.antikytheramechanism.compat.create;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Optional;
import java.util.UUID;

/**
 * Keeps a Sable assembly on Create's continuous rigid transform.
 *
 * <p>Only the deterministic leader actor for an assembly writes its semantic
 * {@code AssemblyPose}. Internal mini BlockPos and BlockStates are never
 * touched.</p>
 */
final class MechanismFrameMovementBehaviour implements MovementBehaviour {
    private static final String BINDING_TAG = "antikytheramechanism_binding";
    private static final String INVALID_CAPTURE_TAG = "antikytheramechanism_invalid_capture";
    private static final String OWNED_STALL_TAG = "antikytheramechanism_owned_stall";
    private static final String REPORTED_TAG = "antikytheramechanism_reported";
    private static final String UPRIGHT_STALL_TAG = "antikytheramechanism_upright_stall";
    private static final String UPRIGHT_REPORTED_TAG = "antikytheramechanism_upright_reported";
    private static final double UPRIGHT_EPSILON = 1.0E-5;

    private final Block frameBlock;

    MechanismFrameMovementBehaviour(Block frameBlock) {
        this.frameBlock = frameBlock;
    }

    @Override
    public void startMoving(MovementContext context) {
        if (!(context.world instanceof ServerLevel serverLevel)) {
            return;
        }
        Optional<UUID> assemblyId = CreateFrameCapture.assemblyId(context.blockEntityData);
        if (assemblyId.isEmpty()) {
            reject(context, "captured Mechanism Frame has no assembly UUID");
            return;
        }
        Optional<CreateFrameCapture.Capture> capture =
                CreateFrameCapture.inspect(context.contraption, frameBlock, assemblyId.get());
        if (capture.isEmpty() || !capture.get().leaderLocalPosition().equals(context.localPos)) {
            return;
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(serverLevel)
                .getAssembly(assemblyId.get())
                .orElse(null);
        if (assembly == null) {
            waitForRecovery(context, assemblyId.get(), "assembly metadata is not available at movement start");
            return;
        }
        Optional<ContraptionPoseBinding> binding = ContraptionPoseBinding.initial(
                assembly,
                capture.get().localFrames(),
                capture.get().leaderLocalPosition());
        if (binding.isEmpty()) {
            reject(context, "Create captured only part of assembly " + assemblyId.get());
            return;
        }
        context.data.put(BINDING_TAG, binding.get().save());
    }

    @Override
    public void tick(MovementContext context) {
        if (!isUpright(context)) {
            context.stall = true;
            context.data.putBoolean(UPRIGHT_STALL_TAG, true);
            if (!context.data.getBoolean(UPRIGHT_REPORTED_TAG)) {
                AntikytheraMechanism.LOGGER.warn(
                        "Paused Create contraption containing a Mechanism Frame: pitch/roll is not enabled yet; only upright yaw motion is supported");
                context.data.putBoolean(UPRIGHT_REPORTED_TAG, true);
            }
            return;
        }
        if (context.data.getBoolean(UPRIGHT_STALL_TAG)) {
            context.stall = false;
            context.data.remove(UPRIGHT_STALL_TAG);
            context.data.remove(UPRIGHT_REPORTED_TAG);
        }
        if (!(context.world instanceof ServerLevel serverLevel) || context.position == null) {
            return;
        }
        if (context.data.getBoolean(INVALID_CAPTURE_TAG)) {
            ownStall(context);
            return;
        }

        Optional<ContraptionPoseBinding> stored = readBinding(context.data);
        UUID assemblyId = stored.map(ContraptionPoseBinding::assemblyId)
                .or(() -> CreateFrameCapture.assemblyId(context.blockEntityData))
                .orElse(null);
        if (assemblyId == null) {
            reject(context, "moving Mechanism Frame lost its assembly UUID");
            return;
        }

        Optional<CreateFrameCapture.Capture> capture =
                CreateFrameCapture.inspect(context.contraption, frameBlock, assemblyId);
        if (capture.isEmpty() || !capture.get().leaderLocalPosition().equals(context.localPos)) {
            return;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
        if (assembly == null) {
            waitForRecovery(context, assemblyId, "assembly metadata is unavailable while its contraption is loaded");
            return;
        }

        Optional<Quaterniond> rotation = currentRotation(context);
        if (rotation.isEmpty()) {
            reject(context, "Create supplied a non-rigid contraption transform for assembly " + assemblyId);
            return;
        }

        ContraptionPoseBinding binding = stored.orElse(null);
        if (binding == null) {
            // Old/incomplete actor NBT can omit startMoving data after a reload.
            // Reconstruct it from the current manager pose and current rotation.
            binding = ContraptionPoseBinding.rebind(
                            assembly,
                            capture.get().localFrames(),
                            capture.get().leaderLocalPosition(),
                            rotation.get())
                    .orElse(null);
            if (binding == null) {
                reject(context, "could not rebind a complete captured assembly " + assemblyId);
                return;
            }
            context.data.put(BINDING_TAG, binding.save());
        } else if (ContraptionPoseBinding.findTranslation(
                        capture.get().localFrames(), assembly.frames()).isEmpty()) {
            reject(context, "moving contraption no longer contains every frame of assembly " + assemblyId);
            return;
        }

        boolean updated = manager.updatePoseTarget(
                assemblyId,
                binding.poseAt(
                        new Vector3d(context.position.x, context.position.y, context.position.z),
                        rotation.get()));
        if (!updated) {
            waitForRecovery(
                    context,
                    assemblyId,
                    "assembly pose updates are temporarily locked by persistent recovery state");
            return;
        }
        releaseOwnedStall(context);
    }

    @Override
    public void stopMoving(MovementContext context) {
        // Contraption.stop invokes this while position and rotation are still
        // valid, immediately before Create computes/places its snapped transform.
        tick(context);
    }

    @Override
    public boolean mustTickWhileDisabled() {
        return true;
    }

    @Override
    public @Nullable ItemStack canBeDisabledVia(MovementContext context) {
        // Pose ownership is structural, not an actor that contraption controls
        // may switch off.
        return null;
    }

    private static Optional<ContraptionPoseBinding> readBinding(CompoundTag contextData) {
        return contextData.contains(BINDING_TAG, Tag.TAG_COMPOUND)
                ? ContraptionPoseBinding.load(contextData.getCompound(BINDING_TAG))
                : Optional.empty();
    }

    private static boolean isUpright(MovementContext context) {
        Vec3 up = context.rotation.apply(new Vec3(0.0, 1.0, 0.0));
        return Math.abs(up.x) <= UPRIGHT_EPSILON
                && Math.abs(up.y - 1.0) <= UPRIGHT_EPSILON
                && Math.abs(up.z) <= UPRIGHT_EPSILON;
    }

    private static Optional<Quaterniond> currentRotation(MovementContext context) {
        Vec3 x = context.rotation.apply(new Vec3(1.0, 0.0, 0.0));
        Vec3 y = context.rotation.apply(new Vec3(0.0, 1.0, 0.0));
        Vec3 z = context.rotation.apply(new Vec3(0.0, 0.0, 1.0));
        return ContraptionRotationMath.fromBasis(
                new Vector3d(x.x, x.y, x.z),
                new Vector3d(y.x, y.y, y.z),
                new Vector3d(z.x, z.y, z.z));
    }

    private static void waitForRecovery(MovementContext context, UUID assemblyId, String reason) {
        ownStall(context);
        if (!context.data.getBoolean(REPORTED_TAG)) {
            AntikytheraMechanism.LOGGER.warn(
                    "Paused Create contraption follower for assembly {}: {}. The binding will be retried.",
                    assemblyId,
                    reason);
            context.data.putBoolean(REPORTED_TAG, true);
        }
    }

    private static void reject(MovementContext context, String reason) {
        context.data.putBoolean(INVALID_CAPTURE_TAG, true);
        ownStall(context);
        if (!context.data.getBoolean(REPORTED_TAG)) {
            AntikytheraMechanism.LOGGER.error(
                    "Rejected unsafe Create contraption movement: {}. No internal mini BlockStates were changed.",
                    reason);
            context.data.putBoolean(REPORTED_TAG, true);
        }
    }

    private static void ownStall(MovementContext context) {
        context.stall = true;
        context.data.putBoolean(OWNED_STALL_TAG, true);
    }

    private static void releaseOwnedStall(MovementContext context) {
        if (context.data.getBoolean(OWNED_STALL_TAG)) {
            context.stall = false;
            context.data.remove(OWNED_STALL_TAG);
            context.data.remove(REPORTED_TAG);
        }
    }
}
