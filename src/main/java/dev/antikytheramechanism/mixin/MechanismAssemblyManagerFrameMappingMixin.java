package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyManagerFrameMappingMixin {
    @Inject(method = "syncFrameBlockEntity", at = @At("RETURN"))
    private static void antikytheramechanism$syncLogicalMapping(
            ServerLevel level, BlockPos position, UUID assemblyId, CallbackInfo ci) {
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(assemblyId).orElse(null);
        if (assembly != null && level.getBlockEntity(position) instanceof MechanismFrameBlockEntity frame) {
            frame.setAssemblyMapping(assembly.id(), assembly.orientation(), assembly.logicalFrameOffset(position));
        }
    }
}
