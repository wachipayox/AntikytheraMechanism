package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import dev.antikytheramechanism.client.CreateContraptionDisassemblySnap;
import dev.antikytheramechanism.client.CreateContraptionFrameBinding;
import dev.antikytheramechanism.client.CreateContraptionRenderTransform;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Makes Create's interpolated contraption transform the visual parent of a captured managed child. */
@Mixin(value = ClientSubLevel.class, priority = 2200)
abstract class ClientSubLevelCreateContraptionPoseMixin {
    @Unique private static final String NAME_PREFIX = "antikythera-";
    @Unique private static final double SNAP_POSITION_EPSILON_SQUARED = 1.0E-10;
    @Unique private static final double SNAP_ORIENTATION_EPSILON = 1.0E-8;

    @Unique private final Pose3d antikytheramechanism$createPose = new Pose3d();
    @Unique private final Quaterniond antikytheramechanism$createOrientation = new Quaterniond();
    @Unique private @Nullable AbstractContraptionEntity antikytheramechanism$entity;
    @Unique private @Nullable BlockPos antikytheramechanism$localOrigin;
    @Unique private FrameOrientation antikytheramechanism$captureOrientation = FrameOrientation.IDENTITY;
    @Unique private @Nullable UUID antikytheramechanism$assemblyId;
    @Unique private long antikytheramechanism$resolvedTick = Long.MIN_VALUE;

    @Inject(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$followCreate(
            float partialTick, CallbackInfoReturnable<Pose3dc> callback) {
        ClientSubLevel child = (ClientSubLevel) (Object) this;
        if (!ManagedClientSubLevelIdentity.isManaged(child)) return;

        UUID assemblyId = antikytheramechanism$assemblyId(child);
        if (assemblyId == null) return;
        long tick = child.getLevel().getGameTime();
        if (antikytheramechanism$resolvedTick != tick
                && (!assemblyId.equals(antikytheramechanism$assemblyId)
                || !antikytheramechanism$bindingValid(assemblyId))) {
            antikytheramechanism$resolvedTick = tick;
            antikytheramechanism$resolve(child, assemblyId);
        }

        AbstractContraptionEntity entity = antikytheramechanism$entity;
        CreateContraptionDisassemblySnap.Snap snap = CreateContraptionDisassemblySnap.get(assemblyId);
        if (snap != null && entity != null && entity.isAlive() && entity.getId() != snap.entityId()) {
            // A later Create capture of the same assembly supersedes an old handoff that never got a
            // chance to converge (for example because its chunk was unloaded during disassembly).
            CreateContraptionDisassemblySnap.clear(assemblyId);
            snap = null;
        }
        if (snap != null) {
            antikytheramechanism$setSyntheticPose(
                    child, snap.anchor(), new Quaterniond(snap.orientation()), partialTick, callback);
            if (antikytheramechanism$sablePoseConverged(child, snap)) {
                // Render the exact snapped pose for this frame as well. On the next render Sable can
                // resume normally without exposing an interpolation tail from the disassembly jump.
                CreateContraptionDisassemblySnap.clear(assemblyId);
            }
            return;
        }

        BlockPos localOrigin = antikytheramechanism$localOrigin;
        if (entity == null || localOrigin == null || !entity.isAlive()) return;

        // Resolve anchor and rotation from the exact same local Matrix4f that Create uses to draw the
        // contraption. This keeps the managed child on Create's render interpolation clock and avoids
        // rebuilding a merely mathematically-equivalent quaternion through applyRotation().
        CreateContraptionRenderTransform.RenderTransform renderTransform =
                CreateContraptionRenderTransform.resolve(
                        entity, Vec3.atCenterOf(localOrigin), partialTick);
        Quaterniond localOrientation = new Quaterniond(renderTransform.orientation())
                .mul(antikytheramechanism$captureOrientation.quaternion(new Quaterniond()))
                .normalize();

        antikytheramechanism$setSyntheticPose(
                child, renderTransform.position(), localOrientation, partialTick, callback);
    }

    @Unique
    private void antikytheramechanism$setSyntheticPose(
            ClientSubLevel child,
            Vec3 anchor,
            Quaterniond localOrientation,
            float partialTick,
            CallbackInfoReturnable<Pose3dc> callback) {
        // A Create contraption living inside a foreign Sable plot reports its interpolated transform
        // in that host's plot coordinates. Compose that local Create transform through the exact host
        // render pose; otherwise the managed child is rendered in the remote plot yard and vanishes.
        ClientSubLevel ownerHost = antikytheramechanism$foreignHost(child, anchor);
        Vector3d worldAnchor = new Vector3d(anchor.x, anchor.y, anchor.z);
        Quaterniond orientation = antikytheramechanism$createOrientation;
        if (ownerHost != null) {
            Pose3dc hostPose = ownerHost.renderPose(partialTick);
            hostPose.transformPosition(worldAnchor);
            orientation.set(hostPose.orientation()).normalize().mul(localOrientation).normalize();
        } else {
            orientation.set(localOrientation);
        }

        Pose3d output = antikytheramechanism$createPose;
        BlockPos plotCenter = child.getPlot().getCenterBlock();

        // Do not reuse Sable's moving centre-of-mass rotation point for this synthetic render pose.
        // The stable semantic point of an Antikythera child is the exact centre of the origin Frame's
        // 2x2x2 mini volume (plotCenter + 1). Choosing that point itself as the render rotationPoint
        // makes the required transform direct: Create's interpolated origin-frame centre is the pose
        // position, and every mini vertex is rotated/scaled around that same semantic centre.
        output.rotationPoint().set(
                plotCenter.getX() + 1.0,
                plotCenter.getY() + 1.0,
                plotCenter.getZ() + 1.0);
        output.position().set(worldAnchor);
        output.scale().set(child.logicalPose().scale());
        output.orientation().set(orientation);
        callback.setReturnValue(output);
    }

    @Unique
    private boolean antikytheramechanism$sablePoseConverged(
            ClientSubLevel child,
            CreateContraptionDisassemblySnap.Snap snap) {
        Vector3d targetAnchor = new Vector3d(snap.anchor().x, snap.anchor().y, snap.anchor().z);
        Quaterniond targetOrientation = new Quaterniond(snap.orientation());
        ClientSubLevel ownerHost = antikytheramechanism$foreignHost(child, snap.anchor());
        if (ownerHost != null) {
            Pose3dc hostPose = ownerHost.logicalPose();
            hostPose.transformPosition(targetAnchor);
            targetOrientation.set(hostPose.orientation())
                    .normalize()
                    .mul(snap.orientation())
                    .normalize();
        }

        BlockPos plotCenter = child.getPlot().getCenterBlock();
        return antikytheramechanism$poseMatches(child.logicalPose(), plotCenter, targetAnchor, targetOrientation)
                && antikytheramechanism$poseMatches(child.lastPose(), plotCenter, targetAnchor, targetOrientation);
    }

    @Unique
    private static boolean antikytheramechanism$poseMatches(
            Pose3dc pose,
            BlockPos plotCenter,
            Vector3d targetAnchor,
            Quaterniondc targetOrientation) {
        Vector3d actualAnchor = new Vector3d(
                plotCenter.getX() + 1.0,
                plotCenter.getY() + 1.0,
                plotCenter.getZ() + 1.0);
        pose.transformPosition(actualAnchor);
        if (actualAnchor.distanceSquared(targetAnchor) > SNAP_POSITION_EPSILON_SQUARED) {
            return false;
        }

        Quaterniondc actual = pose.orientation();
        double dot = Math.abs(
                actual.x() * targetOrientation.x()
                        + actual.y() * targetOrientation.y()
                        + actual.z() * targetOrientation.z()
                        + actual.w() * targetOrientation.w());
        return 1.0 - Math.min(1.0, dot) <= SNAP_ORIENTATION_EPSILON;
    }

    @Unique
    private static @Nullable ClientSubLevel antikytheramechanism$foreignHost(
            ClientSubLevel child,
            Vec3 anchor) {
        ClientSubLevel ownerHost = Sable.HELPER.getContainingClient(anchor);
        if (ownerHost == null || ownerHost == child || ManagedClientSubLevelIdentity.isManaged(ownerHost)) {
            return null;
        }
        return ownerHost;
    }

    @Unique
    private void antikytheramechanism$resolve(ClientSubLevel child, UUID assemblyId) {
        antikytheramechanism$entity = null;
        antikytheramechanism$localOrigin = null;
        antikytheramechanism$assemblyId = assemblyId;
        for (Entity candidate : child.getLevel().entitiesForRendering()) {
            if (!(candidate instanceof AbstractContraptionEntity entity)
                    || !(entity instanceof CreateContraptionClientAccess.EntityCarrier entityAccess)) continue;
            Object object = entityAccess.getAntikytheraContraption();
            if (!(object instanceof Contraption contraption)
                    || !(contraption instanceof CreateContraptionClientAccess.BlockCarrier blockAccess)) continue;
            CreateContraptionFrameBinding.Binding binding = CreateContraptionFrameBinding.find(
                    blockAccess.getAntikytheraBlocks(), assemblyId);
            if (binding == null) continue;
            antikytheramechanism$entity = entity;
            antikytheramechanism$localOrigin = binding.localOrigin();
            antikytheramechanism$captureOrientation = binding.orientation();
            return;
        }
    }

    @Unique
    private boolean antikytheramechanism$bindingValid(UUID assemblyId) {
        AbstractContraptionEntity entity = antikytheramechanism$entity;
        if (entity == null || !entity.isAlive()
                || !(entity instanceof CreateContraptionClientAccess.EntityCarrier access)
                || !(access.getAntikytheraContraption() instanceof CreateContraptionClientAccess.BlockCarrier blocks)) {
            return false;
        }
        return CreateContraptionFrameBinding.find(blocks.getAntikytheraBlocks(), assemblyId) != null;
    }

    @Unique
    private static @Nullable UUID antikytheramechanism$assemblyId(ClientSubLevel child) {
        String name = child.getName();
        if (name == null || !name.startsWith(NAME_PREFIX)) return null;
        try { return UUID.fromString(name.substring(NAME_PREFIX.length())); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
