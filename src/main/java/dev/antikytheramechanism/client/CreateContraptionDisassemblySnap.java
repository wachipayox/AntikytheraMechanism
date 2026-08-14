package dev.antikytheramechanism.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.compat.create.ContraptionRotationMath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
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
