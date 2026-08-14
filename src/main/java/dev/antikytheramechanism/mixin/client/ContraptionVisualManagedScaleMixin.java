package dev.antikytheramechanism.mixin.client;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores the parent Sable pose scale to Create contraption visuals hosted inside a managed child.
 *
 * <p>Sable's Create compatibility mixin replaces {@code ContraptionVisual#setEmbeddingMatrices}
 * and composes the host position and orientation, but omits {@code renderPose.scale()}. Create's
 * Flywheel visual therefore keeps its native 1x geometry even though the entity is logically inside
 * Antikythera's 0.5-scale SubLevel. Patch Sable's handler itself so its matrix is composed as
 * {@code T * R * S * CreateLocal}, matching Sable's regular render path and Sable Scale's existing
 * BlockEntityStorage fix.</p>
 */
@Mixin(value = ContraptionVisual.class, remap = false)
abstract class ContraptionVisualManagedScaleMixin {
    @TargetHandler(
            mixin = "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.contraptions.ContraptionVisualMixin",
            name = "sable$setEmbeddingMatrices")
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;applyLocalTransforms(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void antikytheramechanism$applyManagedHostScale(
            AbstractContraptionEntity entity,
            PoseStack matrix,
            float partialTick,
            Operation<Void> original) {
        ClientSubLevel host = Sable.HELPER.getContainingClient(entity.position());
        if (host != null && ManagedClientSubLevelIdentity.isManaged(host)) {
            Vector3dc scale = host.renderPose(partialTick).scale();
            if (scale.x() != 1.0 || scale.y() != 1.0 || scale.z() != 1.0) {
                matrix.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            }
        }
        original.call(entity, matrix, partialTick);
    }
}
