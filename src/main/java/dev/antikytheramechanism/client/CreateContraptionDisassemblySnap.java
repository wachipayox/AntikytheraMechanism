package dev.antikytheramechanism.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.compat.create.ContraptionRotationMath;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps Create's exact grid-snapped disassembly transform until the managed child catches up. */
public final class CreateContraptionDisassemblySnap {
    private static final Map<UUID, Snap> SNAPS = new HashMap<>();

    private CreateContraptionDisassemblySnap() {
    }

    public static void capture(int entityId, StructureTransform transform) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        Entity rawEntity = minecraft.level.getEntity(entityId);
        if (!(rawEntity instanceof AbstractContraptionEntity entity)
                || !(entity instanceof CreateContraptionClientAccess.EntityCarrier entityAccess)
                || !(entityAccess.getAntikytheraContraption() instanceof Contraption contraption)
                || !(contraption instanceof CreateContraptionClientAccess.BlockCarrier blockAccess)) {
            return;
        }

        Quaterniond createRotation = ContraptionRotationMath.fromBasis(
                        vector(transform.applyWithoutOffsetUncentered(new Vec3(1, 0, 0))),
                        vector(transform.applyWithoutOffsetUncentered(new Vec3(0, 1, 0))),
                        vector(transform.applyWithoutOffsetUncentered(new Vec3(0, 0, 1))))
                .orElse(null);
        if (createRotation == null) return;

        for (CreateContraptionFrameBinding.Binding binding
                : CreateContraptionFrameBinding.findAll(blockAccess.getAntikytheraBlocks()).values()) {
            Vec3 anchor = transform.apply(Vec3.atCenterOf(binding.localOrigin()));
            Quaterniond orientation = new Quaterniond(createRotation)
                    .mul(binding.orientation().quaternion(new Quaterniond()))
                    .normalize();
            SNAPS.put(binding.assemblyId(), new Snap(entityId, anchor, orientation));
        }
    }

    public static @Nullable Snap get(UUID assemblyId) {
        return SNAPS.get(assemblyId);
    }

    /**
     * Returns the pending Create handoff only while its physical docking target is still authoritative.
     *
     * <p>The snap is deliberately not time based: a slow or temporarily unloaded Create disassembly
     * may legitimately need the handoff for longer than a few ticks. Once the target chunk is known,
     * however, the snap only describes a real docking if the assembly's origin Frame still occupies
     * that exact snapped cell. A later Sable/Simulated relocation removes that Frame before moving it
     * to another host, so retaining the old snap would otherwise keep rendering the managed child at
     * a stale Create position forever.</p>
     *
     * <p>Create's snapped transform can contain pitch/roll even though a placed Mechanism Frame can
     * only represent an upright {@code HORIZONTAL_FACING}. As soon as the destination origin Frame is
     * present, its synchronized BlockState becomes the physical authority for the handoff orientation;
     * the full snapped orientation remains stored only until then and continues to describe the moving
     * contraption while no static Frame exists.</p>
     */
    public static @Nullable Snap getWhileDocked(Level level, UUID assemblyId) {
        Snap snap = SNAPS.get(assemblyId);
        if (snap == null) return null;

        BlockPos targetFrame = BlockPos.containing(snap.anchor());
        if (!level.hasChunkAt(targetFrame)) {
            // Lack of client knowledge is not evidence that the Create docking became invalid.
            return snap;
        }

        if (level.getBlockState(targetFrame).is(ModRegistries.MECHANISM_FRAME.get())
                && level.getBlockEntity(targetFrame) instanceof MechanismFrameBlockEntity frame
                && assemblyId.equals(frame.getAssemblyId())
                && BlockPos.ZERO.equals(frame.getLogicalFrameOffset())) {
            Quaterniond physicalOrientation = frame.getPhysicalFrameOrientation().quaternion(new Quaterniond());
            return new Snap(snap.entityId(), snap.anchor(), physicalOrientation);
        }

        SNAPS.remove(assemblyId);
        return null;
    }

    public static void clear(UUID assemblyId) {
        SNAPS.remove(assemblyId);
    }

    private static Vector3d vector(Vec3 value) {
        return new Vector3d(value.x, value.y, value.z);
    }

    public record Snap(int entityId, Vec3 anchor, Quaterniondc orientation) {
        public Snap {
            orientation = new Quaterniond(orientation);
        }
    }
}
