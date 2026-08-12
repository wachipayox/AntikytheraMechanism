package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the scale component missing from Sable's block-damage decal transform.
 *
 * <p>Sable's block_decal_render LevelRendererMixin replaces vanilla's translation with the
 * SubLevel's projected position and orientation immediately before PoseStack.last(), but it does
 * not apply renderPose.scale(). The destruction overlay therefore stays one full block large over
 * a 0.5-scale miniblock. Inject immediately after that PoseStack.last() call and scale the same
 * stack pose for managed Antikythera SubLevels only.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 900)
abstract class LevelRendererManagedBreakScaleMixin {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;last()Lcom/mojang/blaze3d/vertex/PoseStack$Pose;",
                    shift = At.Shift.AFTER))
    private void antikytheramechanism$scaleManagedBreakOverlay(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo callback,
            @Local(ordinal = 0) PoseStack poseStack,
            @Local(ordinal = 0) BlockPos pos) {
        if (this.level == null || pos == null) {
            return;
        }

        ClientSubLevel subLevel = (ClientSubLevel) Sable.HELPER.getContaining(this.level, pos);
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        Vector3dc scale = subLevel.renderPose().scale();
        poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
    }
}
