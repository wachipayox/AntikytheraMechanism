package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.compat.create.ContraptionRotationMath;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

/** Makes Create's interpolated contraption transform the visual parent of a captured managed child. */
@Mixin(value = ClientSubLevel.class, priority = 2200)
abstract class ClientSubLevelCreateContraptionPoseMixin {
    @Unique private static final String NAME_PREFIX = "antikythera-";
    @Unique private static final String ASSEMBLY_ID_TAG = "assembly_id";
    @Unique private static final String ORIENTATION_TAG = "frame_orientation";
    @Unique private static final String LOGICAL_OFFSET_TAG = "logical_frame_offset";

    @Unique private final Pose3d antikytheramechanism$createPose = new Pose3d();
    @Unique private final Quaterniond antikytheramechanism$createOrientation = new Quaterniond();
    @Unique private final Vector3d antikytheramechanism$childOffset = new Vector3d();
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
        BlockPos localOrigin = antikytheramechanism$localOrigin;
        if (entity == null || localOrigin == null || !entity.isAlive()) return;

        Quaterniond createRotation = ContraptionRotationMath.fromBasis(
                        vector(entity.applyRotation(new Vec3(1, 0, 0), partialTick)),
                        vector(entity.applyRotation(new Vec3(0, 1, 0), partialTick)),
                        vector(entity.applyRotation(new Vec3(0, 0, 1), partialTick)))
                .orElse(null);
        if (createRotation == null) return;

        Quaterniond localOrientation = new Quaterniond(createRotation)
                .mul(antikytheramechanism$captureOrientation.quaternion(new Quaterniond()))
                .normalize();
        Vec3 anchor = entity.toGlobalVector(Vec3.atCenterOf(localOrigin), partialTick);

        // A Create contraption living inside a foreign Sable plot reports its interpolated transform
        // in that host's plot coordinates. Compose that local Create transform through the exact host
        // render pose; otherwise the managed child is rendered in the remote plot yard and vanishes.
        ClientSubLevel ownerHost = Sable.HELPER.getContainingClient(anchor);
        Vector3d worldAnchor = new Vector3d(anchor.x, anchor.y, anchor.z);
        Quaterniond orientation = antikytheramechanism$createOrientation;
        if (ownerHost != null) {
            if (ManagedClientSubLevelIdentity.isManaged(ownerHost)) return;
            Pose3dc hostPose = ownerHost.renderPose(partialTick);
            hostPose.transformPosition(worldAnchor);
            orientation.set(hostPose.orientation()).normalize().mul(localOrientation).normalize();
        } else {
            orientation.set(localOrientation);
        }

        Pose3d output = antikytheramechanism$createPose;
        // Create already supplies the sole temporal interpolation for this render path. The child's
        // rotation point and half-scale are structural properties used to map its stable plot anchor;
        // interpolating them independently against Sable's lastPose introduces a rotating pivot error
        // that becomes visible as protrusion/z-fighting at particular contraption yaw angles.
        output.rotationPoint().set(child.logicalPose().rotationPoint());
        output.scale().set(child.logicalPose().scale());
        output.orientation().set(orientation);

        BlockPos plotCenter = child.getPlot().getCenterBlock();
        Vector3d offset = antikytheramechanism$childOffset
                .set(plotCenter.getX() + 1.0, plotCenter.getY() + 1.0, plotCenter.getZ() + 1.0)
                .sub(output.rotationPoint())
                .mul(output.scale());
        orientation.transform(offset);
        output.position().set(worldAnchor).sub(offset);
        callback.setReturnValue(output);
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
            Binding binding = antikytheramechanism$findBinding(blockAccess.getAntikytheraBlocks(), assemblyId);
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
        return antikytheramechanism$findBinding(blocks.getAntikytheraBlocks(), assemblyId) != null;
    }

    @Unique
    private static @Nullable Binding antikytheramechanism$findBinding(
            Map<BlockPos, StructureBlockInfo> blocks, UUID assemblyId) {
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            StructureBlockInfo info = entry.getValue();
            CompoundTag nbt = info.nbt();
            if (!info.state().is(ModRegistries.MECHANISM_FRAME.get())
                    || nbt == null || !nbt.hasUUID(ASSEMBLY_ID_TAG)
                    || !assemblyId.equals(nbt.getUUID(ASSEMBLY_ID_TAG))) continue;
            FrameOrientation orientation = nbt.contains(ORIENTATION_TAG)
                    ? FrameOrientation.load(nbt.getCompound(ORIENTATION_TAG)) : FrameOrientation.IDENTITY;
            BlockPos logicalOffset = nbt.contains(LOGICAL_OFFSET_TAG)
                    ? BlockPos.of(nbt.getLong(LOGICAL_OFFSET_TAG)) : BlockPos.ZERO;
            BlockPos localOrigin = entry.getKey().subtract(orientation.toPhysical(logicalOffset));
            return new Binding(localOrigin.immutable(), orientation);
        }
        return null;
    }

    @Unique
    private static @Nullable UUID antikytheramechanism$assemblyId(ClientSubLevel child) {
        String name = child.getName();
        if (name == null || !name.startsWith(NAME_PREFIX)) return null;
        try { return UUID.fromString(name.substring(NAME_PREFIX.length())); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @Unique
    private static Vector3d vector(Vec3 value) {
        return new Vector3d(value.x, value.y, value.z);
    }

    @Unique
    private record Binding(BlockPos localOrigin, FrameOrientation orientation) {}
}
