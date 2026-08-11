package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** FrameGraph, not interior block connectivity, is authoritative for managed assemblies. */
@Mixin(value = ServerSubLevel.class, remap = false)
abstract class ServerSubLevelFrameGraphMixin {
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager;tick()V"))
    private void antikytheramechanism$skipPhysicalSplit(SubLevelHeatMapManager heatMapManager) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        if (MechanismSubLevelService.getOwnerAssemblyId(self) == null) {
            heatMapManager.tick();
        }
    }
}
