package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyFrameCellOrientationMixin {
    @Redirect(
            method = "syncFrameState",
            at = @At(value = "INVOKE", target = "Ldev/antikytheramechanism/sublevel/MiniCoordinateMapper;frameToMini(Ldev/antikytheramechanism/assembly/MechanismAssembly;Lnet/minecraft/core/BlockPos;III)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos antikytheramechanism$physicalOccupancy(
            MechanismAssembly assembly, BlockPos frame, int x, int y, int z) {
        return MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, x, y, z);
    }
}
