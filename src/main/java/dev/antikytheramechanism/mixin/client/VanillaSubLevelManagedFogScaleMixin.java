package dev.antikytheramechanism.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Compensates vanilla terrain fog for Antikythera-managed scaled Sable SubLevels.
 *
 * <p>Minecraft 1.21.1 terrain shaders calculate {@code vertexDistance} from
 * {@code Position + ChunkOffset}, before {@code ModelViewMat} is applied. Sable Scale correctly
 * renders geometry by putting the camera delta into pre-scale {@code ChunkOffset} coordinates and
 * applying the SubLevel scale in {@code ModelViewMat}. For a half-scale SubLevel that means the fog
 * shader sees roughly twice the physical distance even though the final geometry is positioned
 * correctly.</p>
 *
 * <p>Scale the fog planes only for the immediate draw of an Antikythera-managed child and restore
 * the active shader immediately afterwards. Uniform scaling is exact. For a non-uniform pose, use
 * the smallest absolute axis scale so no direction is fogged earlier than its physical world
 * distance; that is deliberately conservative because vanilla exposes only scalar fog planes.</p>
 */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
abstract class VanillaSubLevelManagedFogScaleMixin {
    private static final double MIN_SCALE = 1.0E-6;

    @Redirect(
            method = "renderSectionLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaChunkedSubLevelRenderData;renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V"))
    private void antikytheramechanism$renderManagedWithPhysicalFogDistance(
            VanillaChunkedSubLevelRenderData renderData,
            RenderType layer,
            ShaderInstance shader,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ) {
        ClientSubLevel subLevel = renderData.getSubLevel();
        if (!ManagedClientSubLevelIdentity.isManaged(subLevel)) {
            renderData.renderChunkedSubLevel(layer, shader, modelView, cameraX, cameraY, cameraZ);
            return;
        }

        Vector3dc scale = subLevel.renderPose().scale();
        double minimumScale = Math.min(
                Math.abs(scale.x()),
                Math.min(Math.abs(scale.y()), Math.abs(scale.z())));
        if (minimumScale < MIN_SCALE || Math.abs(minimumScale - 1.0) < MIN_SCALE) {
            renderData.renderChunkedSubLevel(layer, shader, modelView, cameraX, cameraY, cameraZ);
            return;
        }

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float fogDistanceMultiplier = (float) (1.0 / minimumScale);
        boolean changedStart = shader.FOG_START != null;
        boolean changedEnd = shader.FOG_END != null;

        if (changedStart) {
            shader.FOG_START.set(fogStart * fogDistanceMultiplier);
            shader.FOG_START.upload();
        }
        if (changedEnd) {
            shader.FOG_END.set(fogEnd * fogDistanceMultiplier);
            shader.FOG_END.upload();
        }

        try {
            renderData.renderChunkedSubLevel(layer, shader, modelView, cameraX, cameraY, cameraZ);
        } finally {
            if (changedStart) {
                shader.FOG_START.set(fogStart);
                shader.FOG_START.upload();
            }
            if (changedEnd) {
                shader.FOG_END.set(fogEnd);
                shader.FOG_END.upload();
            }
        }
    }
}
