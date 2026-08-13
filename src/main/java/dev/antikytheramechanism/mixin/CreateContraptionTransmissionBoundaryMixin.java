package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.compat.create.transmission.TransmissionLinkCoordinator;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.UUID;

@Pseudo
@Mixin(value = CreateContraptionBoundaryLifecycle.class, remap = false)
abstract class CreateContraptionTransmissionBoundaryMixin {
    @Inject(method = "disconnect", at = @At("HEAD"), remap = false)
    private static void antikytheramechanism$quiesce(
            ServerLevel level, Collection<UUID> ids, CallbackInfo ci) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID id : ids) {
            manager.getAssembly(id).ifPresent(assembly ->
                    TransmissionLinkCoordinator.quiesceAssembly(level, assembly));
        }
    }

    @Inject(method = "reconnect", at = @At("RETURN"), remap = false)
    private static void antikytheramechanism$rebuild(
            ServerLevel level, Collection<UUID> ids, CallbackInfo ci) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID id : ids) {
            manager.getAssembly(id).ifPresent(assembly ->
                    TransmissionLinkCoordinator.rebuildAssemblyKinetics(level, assembly));
        }
    }
}
