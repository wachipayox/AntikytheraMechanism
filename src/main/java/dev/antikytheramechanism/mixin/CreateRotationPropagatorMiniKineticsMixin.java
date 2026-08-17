package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.compat.create.CreateMiniKineticTopology;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Supplies cross-assembly mini geometry while leaving Create's kinetic equations untouched. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
abstract class CreateRotationPropagatorMiniKineticsMixin {
    @Inject(method = "getPotentialNeighbourLocations", at = @At("RETURN"), remap = false)
    private static void antikytheramechanism$appendVisibleMiniDiagonals(
            KineticBlockEntity blockEntity,
            CallbackInfoReturnable<List<BlockPos>> callback) {
        CreateMiniKineticTopology.appendVirtualDiagonalNeighbours(blockEntity, callback.getReturnValue());
    }

    @ModifyExpressionValue(
            method = "getRotationSpeedModifier",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"),
            remap = false)
    private static BlockPos antikytheramechanism$useVisibleMiniDifference(
            BlockPos original,
            KineticBlockEntity from,
            KineticBlockEntity to) {
        return CreateMiniKineticTopology.relativePosition(from, to, original);
    }
}
