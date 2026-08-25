package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import dev.antikytheramechanism.client.ManagedMiniKineticPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Makes Create's checkerboard shaft/cog phase depend on visible physical mini coordinates. */
@Mixin(value = KineticBlockEntityVisual.class, remap = false)
abstract class KineticBlockEntityVisualManagedPhaseMixin {
    @ModifyVariable(method = "rotationOffset", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Vec3i antikytheramechanism$usePhysicalManagedMiniPosition(Vec3i position) {
        if (!(position instanceof BlockPos blockPos)) {
            return position;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return position;
        }
        BlockPos physical = ManagedMiniKineticPhase.physicalMiniPosition(level, blockPos);
        return physical == null ? position : physical;
    }
}
