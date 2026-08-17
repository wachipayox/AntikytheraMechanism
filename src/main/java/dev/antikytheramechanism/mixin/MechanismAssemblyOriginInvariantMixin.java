package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.AssemblyOriginInvariantService;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Repairs the retained assembly basis after every split attempt, including one-component early exits. */
@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyOriginInvariantMixin {
    @Inject(method = "splitDisconnectedAssembly", at = @At("RETURN"), remap = false)
    private void antikytheramechanism$repairRetainedOrigin(
            ServerLevel level,
            MechanismAssembly source,
            CallbackInfo callback) {
        AssemblyOriginInvariantService.repairIfNeeded(level, source);
    }
}
