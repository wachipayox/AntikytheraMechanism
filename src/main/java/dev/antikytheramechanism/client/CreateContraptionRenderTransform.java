package dev.antikytheramechanism.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Mirrors Create's render-space contraption transform, including interpolated entity translation. */
public final class CreateContraptionRenderTransform {
    private static final Vec3 ROTATION_OFFSET = Vec3.atCenterOf(BlockPos.ZERO);

    private CreateContraptionRenderTransform() {
    }

    public static Vec3 toRenderedWorld(
            AbstractContraptionEntity entity,
            Vec3 localPosition,
            float partialTick) {
        Vec3 rotated = entity.applyRotation(localPosition.subtract(ROTATION_OFFSET), partialTick)
                .add(ROTATION_OFFSET);
        Vec3 interpolatedAnchor = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()));
        return rotated.add(interpolatedAnchor);
    }
}
