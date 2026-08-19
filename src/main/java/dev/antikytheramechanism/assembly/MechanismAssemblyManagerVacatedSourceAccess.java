package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;

/** Internal mixin bridge for handing a physically vacated Create source to a new Frame. */
public interface MechanismAssemblyManagerVacatedSourceAccess {
    boolean antikytheramechanism$releaseContraptionSourceFrame(BlockPos framePos);
}
