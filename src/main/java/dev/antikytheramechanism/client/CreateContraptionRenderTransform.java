package dev.antikytheramechanism.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Mirrors Create's exact render-space contraption transform, including render-only local offsets. */
public final class CreateContraptionRenderTransform {
    private CreateContraptionRenderTransform() {
    }

    /**
     * Resolves the local model transform with the same interpolation clock Create's
     * {@code ContraptionMatrices.setup()} uses, while retaining the caller-provided partial tick for
     * the outer entity translation supplied by Minecraft's entity renderer.
     *
     * <p>Both the transformed anchor and rotation are extracted from the same Matrix4f produced by
     * {@link AbstractContraptionEntity#applyLocalTransforms(PoseStack, float)}. Reconstructing the
     * rotation independently through {@code applyRotation()} produced a mathematically equivalent but
     * not float-identical matrix; that sub-pixel disagreement is enough to make coplanar Frame/mini
     * faces alternate in the depth buffer while the camera moves.</p>
     */
    public static RenderTransform resolve(
            AbstractContraptionEntity entity,
            Vec3 localPosition,
            float entityPartialTick) {
        float createPartialTick = AnimationTickHolder.getPartialTicks();
        PoseStack localTransform = new PoseStack();
        entity.applyLocalTransforms(localTransform, createPartialTick);

        Vector3f transformed = new Vector3f(
                (float) localPosition.x,
                (float) localPosition.y,
                (float) localPosition.z);
        localTransform.last().pose().transformPosition(transformed);

        Quaternionf matrixRotation = localTransform.last().pose()
                .getNormalizedRotation(new Quaternionf())
                .normalize();
        Quaterniond orientation = new Quaterniond(
                matrixRotation.x(),
                matrixRotation.y(),
                matrixRotation.z(),
                matrixRotation.w());

        Vec3 position = new Vec3(
                Mth.lerp(entityPartialTick, entity.xOld, entity.getX()) + transformed.x(),
                Mth.lerp(entityPartialTick, entity.yOld, entity.getY()) + transformed.y(),
                Mth.lerp(entityPartialTick, entity.zOld, entity.getZ()) + transformed.z());
        return new RenderTransform(position, orientation);
    }

    public record RenderTransform(Vec3 position, Quaterniond orientation) {
    }
}
