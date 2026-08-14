package dev.antikytheramechanism.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores the Sable host scale to Create contraption visuals rendered from managed SubLevels.
 *
 * <p>Sable replaces {@code ContraptionVisual#setEmbeddingMatrices} for contraptions located in a
 * SubLevel, but its Create compatibility path composes only the host translation and orientation.
 * Run after that stable Create call site and replace only the embedding transform with the complete
 * {@code T * R * S * CreateLocal} matrix. This deliberately does not inject into Sable's generated
 * mixin handler, so it is independent of cross-mixin handler names and priorities.</p>
 */
@Mixin(value = ContraptionVisual.class, remap = false)
abstract class ContraptionVisualManagedScaleMixin extends AbstractEntityVisual<AbstractContraptionEntity> {
    @Shadow
    @Final
    protected VisualEmbedding embedding;

    @Shadow
    @Final
    private PoseStack contraptionMatrix;

    protected ContraptionVisualManagedScaleMixin(
            VisualizationContext context,
            AbstractContraptionEntity entity,
            float partialTick) {
        super(context, entity, partialTick);
    }

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/render/ContraptionVisual;setEmbeddingMatrices(F)V",
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$afterInitialEmbeddingMatrices(
            VisualizationContext context,
            AbstractContraptionEntity entity,
            float partialTick,
            CallbackInfo ci) {
        antikytheramechanism$applyManagedHostScale(partialTick);
    }

    @Inject(
            method = "beginFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/render/ContraptionVisual;setEmbeddingMatrices(F)V",
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$afterFrameEmbeddingMatrices(
            DynamicVisual.Context context,
            CallbackInfo ci) {
        antikytheramechanism$applyManagedHostScale(context.partialTick());
    }

    @Unique
    private void antikytheramechanism$applyManagedHostScale(float partialTick) {
        ClientSubLevel host = Sable.HELPER.getContainingClient(this.entity.position());
        if (host == null || !ManagedClientSubLevelIdentity.isManaged(host)) {
            return;
        }

        Pose3dc renderPose = host.renderPose(partialTick);
        Vector3dc scale = renderPose.scale();
        if (scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0) {
            return;
        }

        Vec3i origin = this.renderOrigin();
        Vector3d pos = new Vector3d();
        if (this.entity.isPrevPosInvalid()) {
            pos.set(this.entity.getX(), this.entity.getY(), this.entity.getZ());
        } else {
            pos.set(
                    Mth.lerp(partialTick, this.entity.xo, this.entity.getX()),
                    Mth.lerp(partialTick, this.entity.yo, this.entity.getY()),
                    Mth.lerp(partialTick, this.entity.zo, this.entity.getZ()));
        }

        renderPose.transformPosition(pos).sub(origin.getX(), origin.getY(), origin.getZ());

        this.contraptionMatrix.setIdentity();
        this.contraptionMatrix.translate(pos.x, pos.y, pos.z);
        this.contraptionMatrix.mulPose(new Quaternionf(renderPose.orientation()));
        this.contraptionMatrix.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        this.entity.applyLocalTransforms(this.contraptionMatrix, partialTick);
        this.embedding.transforms(
                this.contraptionMatrix.last().pose(),
                this.contraptionMatrix.last().normal());
    }
}
