package dev.antikytheramechanism.compat.create;

import net.minecraft.core.BlockPos;

/** Mixin bridge for Create's protected anchoring-block predicate. */
public interface CreateContraptionAnchorAccess {
    boolean antikytheramechanism$isAnchoringBlockAt(BlockPos position);
}
