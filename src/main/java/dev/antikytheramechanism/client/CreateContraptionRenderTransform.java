package dev.antikytheramechanism.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Mirrors Create's exact render-space contraption transform, including render-only local offsets. */
public final class CreateContraptionRenderTransform {
    private CreateContraptionRenderTransform() {
    }

    public static Vec3 toRenderedWorld(
            AbstractContraptionEntity entity,
            Vec3 localPosition,
            float partialTick) {
        // Do not rebuild Create's visual transform from applyRotation(). The contraption renderer
        // applies applyLocalTransforms(), which also contains render-only translations such as
        // Flywheel's deterministic nudge(getId()) and vehicle/parent-contraption corrections. Missing
        // even the tiny nudge is enough for an exactly flush 0.5-scale mini face to z-fight against
        // the carried Frame on only the faces toward which that nudge points.
        PoseStack localTransform = new PoseStack();
        entity.applyLocalTransforms(localTransform, partialTick);
        Vector3f transformed = new Vector3f(
                (float) localPosition.x,
                (float) localPosition.y,
                (float) localPosition.z);
        localTransform.last().pose().transformPosition(transformed);

        // EntityRenderDispatcher supplies this interpolated translation outside Create's local model
        // matrix. Add the same absolute anchor translation here because Sable expects a world-space
        // pose rather than rendering inside the contraption entity's PoseStack.
        return new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()) + transformed.x(),
                Mth.lerp(partialTick, entity.yOld, entity.getY()) + transformed.y(),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()) + transformed.z());
    }
}
