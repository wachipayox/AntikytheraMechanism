package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Set;

/**
 * Mixin-backed extension for the small amount of extra crash-recovery state needed when a player
 * reuses a parent position vacated by an in-flight Create contraption.
 */
public interface PendingContraptionMoveReleaseAccess {
    Set<BlockPos> antikytheramechanism$getReleasedSourceFrames();

    void antikytheramechanism$setReleasedSourceFrames(Collection<BlockPos> frames);
}
