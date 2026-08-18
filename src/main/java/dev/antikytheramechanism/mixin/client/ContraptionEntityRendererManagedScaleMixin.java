package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Applies a managed SubLevel's physical scale to Create's non-Flywheel contraption render path.
 *
 * <p>{@link ContraptionVisualManagedScaleMixin} fixes the Flywheel embedding used for ordinary
 * contraption block geometry. Block entities such as chests are deliberately rendered later by
 * {@code ContraptionEntityRenderer -> BlockEntityRenderHelper}, which uses the entity renderer's
 * PoseStack instead of that embedding. Sable 2.0.3 composes SubLevel position/orientation around
 * EntityRenderDispatcher but does not compose the SubLevel scale there, leaving those BERs at 1x.
 *
 * <p>Scale the renderer-local stack around the already-translated entity origin. This keeps Create's
 * internal local transforms intact, also covers its vanilla/non-Flywheel fallback, and does not touch
 * the separately scaled Flywheel visual.</p>
 */
@Mixin(value = ContraptionEntityRenderer.class, remap = false)
abstract class ContraptionEntityRendererManagedScaleMixin {
    @WrapMethod(method = "render", remap = false)
    private void antikytheramechanism$applyManagedHostScale(
            AbstractContraptionEntity entity,
            float yaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            Operation<Void> original) {
        ClientSubLevel host = Sable.HELPER.getContainingClient(entity.position());
        if (host == null || !ManagedClientSubLevelIdentity.isManaged(host)) {
            original.call(entity, yaw, partialTicks, poseStack, buffers, packedLight);
            return;
        }

        Pose3dc renderPose = host.renderPose(partialTicks);
        Vector3dc scale = renderPose.scale();
        if (scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0) {
            original.call(entity, yaw, partialTicks, poseStack, buffers, packedLight);
            return;
        }

        poseStack.pushPose();
        try {
            poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            original.call(entity, yaw, partialTicks, poseStack, buffers, packedLight);
        } finally {
            poseStack.popPose();
        }
    }
}
