package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.RedstoneBoundaryUpdateCenterBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelRedstoneBoundaryUpdateCenterMixin {
    @Inject(method = "updateNeighborsAt", at = @At("HEAD"))
    private void antikytheramechanism$mirrorRedstoneBoundaryUpdateCenter(
            BlockPos position,
            Block sourceBlock,
            CallbackInfo callback) {
        RedstoneBoundaryUpdateCenterBridge.mirror((ServerLevel) (Object) this, position, sourceBlock);
    }
}
