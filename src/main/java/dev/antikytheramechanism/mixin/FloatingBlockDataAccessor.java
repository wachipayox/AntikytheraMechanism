package dev.antikytheramechanism.mixin;

import dev.ryanhcode.sable.physics.floating_block.FloatingBlockData;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses the aggregate moments Sable already calculated for one floating-material cluster. */
@Mixin(FloatingBlockData.class)
public interface FloatingBlockDataAccessor {
    @Accessor("outerProduct")
    Matrix3d antikytheramechanism$getOuterProduct();

    @Accessor("weightedPosition")
    Vector3d antikytheramechanism$getWeightedPosition();

    @Accessor("totalScale")
    double antikytheramechanism$getTotalScale();

    @Accessor("totalScale")
    void antikytheramechanism$setTotalScale(double value);

    @Accessor("blockCount")
    int antikytheramechanism$getBlockCount();

    @Accessor("blockCount")
    void antikytheramechanism$setBlockCount(int value);
}
