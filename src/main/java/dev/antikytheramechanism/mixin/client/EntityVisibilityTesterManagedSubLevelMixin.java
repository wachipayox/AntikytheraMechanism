package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.engine_room.flywheel.lib.visual.EntityVisibilityTester;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tests Flywheel entity visuals from Antikythera-managed mini SubLevels in world render space.
 *
 * <p>Flywheel's normal {@link EntityVisibilityTester} subtracts the world render origin directly
 * from the entity's culling AABB. For entities stored inside a Sable SubLevel that AABB is still in
 * SubLevel storage coordinates, while the visual is later moved into world space by a
 * VisualEmbedding. Near the camera frustum boundary this can therefore cull a correctly-positioned
 * mini contraption as though it lived at its untransformed storage position.</p>
 *
 * <p>Keep Flywheel's normal current/last-visible-AABB bookkeeping and only replace the final sphere
 * test for managed children. The AABB center is transformed through the current Sable render pose;
 * its conservative sphere radius is scaled by the largest pose axis, so rotation needs no special
 * handling.</p>
 */
@Mixin(value = EntityVisibilityTester.class, remap = false)
abstract class EntityVisibilityTesterManagedSubLevelMixin {
    private static final double SQRT_3_OVER_2 = 0.8660254037844386;

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private Vec3i renderOrigin;

    @Shadow
    @Final
    private float scale;

    @Inject(method = "adjustAndTestAABB", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$testManagedAabbInWorldSpace(
            FrustumIntersection frustum,
            AABB aabb,
            CallbackInfoReturnable<Boolean> cir) {
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(this.entity);
        if (subLevel == null || !ManagedClientSubLevelIdentity.isManaged(subLevel)) {
            return;
        }

        Pose3dc renderPose = subLevel.renderPose();
        Vector3d center = new Vector3d(
                (aabb.minX + aabb.maxX) * 0.5,
                (aabb.minY + aabb.maxY) * 0.5,
                (aabb.minZ + aabb.maxZ) * 0.5);
        renderPose.transformPosition(center);

        Vector3dc poseScale = renderPose.scale();
        double maximumPoseScale = Math.max(
                Math.abs(poseScale.x()),
                Math.max(Math.abs(poseScale.y()), Math.abs(poseScale.z())));
        double maximumAabbSize = Math.max(aabb.getXsize(), Math.max(aabb.getYsize(), aabb.getZsize()));
        float radius = (float) (maximumAabbSize * SQRT_3_OVER_2 * this.scale * maximumPoseScale);

        cir.setReturnValue(frustum.testSphere(
                (float) (center.x - this.renderOrigin.getX()),
                (float) (center.y - this.renderOrigin.getY()),
                (float) (center.z - this.renderOrigin.getZ()),
                radius));
    }
}
