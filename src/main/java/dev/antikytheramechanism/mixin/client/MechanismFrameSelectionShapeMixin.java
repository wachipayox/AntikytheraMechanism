package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameSelectionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes only the client outline/picking cage slightly easier to target. */
@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameSelectionShapeMixin {
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useForgivingClientSelectionShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> callback) {
        if (level instanceof Level actualLevel && actualLevel.isClientSide()) {
            callback.setReturnValue(MechanismFrameSelectionShape.shape(state));
        }
    }
}
