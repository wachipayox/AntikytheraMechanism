package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Processes only explicitly scheduled portable schematic restores; ordinary Frame ticks are unchanged. */
@Mixin(MechanismFrameBlock.class)
public abstract class MechanismFramePortableRestoreMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void antikytheramechanism$restorePortableMiniContent(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            CallbackInfo ci) {
        if (level.getBlockEntity(pos) instanceof MechanismFrameBlockEntity frame) {
            frame.processPortableRestore(level);
        }
    }
}
