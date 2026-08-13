package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.ManagedFrameMassPolicy;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies Sable with the effective shell + mini-payload mass of a Mechanism Frame. */
@Mixin(PhysicsBlockPropertyHelper.class)
abstract class PhysicsBlockPropertyHelperFrameMassMixin {
    @Inject(method = "getMass", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$useManagedFrameMass(
            BlockGetter level,
            BlockPos pos,
            BlockState state,
            CallbackInfoReturnable<Double> callback) {
        if (state.is(ModRegistries.MECHANISM_FRAME.get())) {
            callback.setReturnValue(ManagedFrameMassPolicy.effectiveFrameMass(level, pos));
        }
    }
}
