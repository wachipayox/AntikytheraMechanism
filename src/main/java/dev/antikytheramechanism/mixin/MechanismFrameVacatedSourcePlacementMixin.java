package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.ContraptionSourceRelease;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-enters normal Frame registration only for a genuinely new Frame placed into an extracted
 * Create source. The block's ordinary onPlace correctly suppresses actual relocation writes, so the
 * distinction is made from the new BlockEntity having no carried assembly id.
 */
@Mixin(value = MechanismFrameBlock.class, remap = false)
abstract class MechanismFrameVacatedSourcePlacementMixin {
    @Inject(method = "onPlace", at = @At("RETURN"), remap = false)
    private void antikytheramechanism$registerReplacementAtVacatedSource(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean movedByPiston,
            CallbackInfo callback) {
        if (oldState.is(state.getBlock()) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof MechanismFrameBlockEntity frame)
                || frame.getAssemblyId() != null) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        if (ContraptionSourceRelease.vacatedSourceReservation(manager, pos) != null) {
            manager.onFramePlaced(serverLevel, pos);
        }
    }
}
