package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniContentChangeBus;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Emits assembly-scoped invalidation after Sable has committed an effective managed mini block change. */
@Mixin(value = ServerLevelPlot.class, remap = false)
abstract class ServerLevelPlotMiniContentChangeMixin {
    @Inject(method = "onBlockChange", at = @At("RETURN"))
    private void antikytheramechanism$notifyManagedMiniContentChanged(
            BlockPos pos,
            BlockState state,
            CallbackInfo callback) {
        ServerSubLevel subLevel = ((ServerLevelPlot) (Object) this).getSubLevel();
        UUID assemblyId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (assemblyId == null || !(subLevel.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MiniContentChangeBus.notifyChanged(level, assemblyId);
    }
}
