package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/** Shared bucket-fluid policy for Frame children and detached Antikythera mini-physics bodies. */
public final class MiniFluidPolicy {
    private MiniFluidPolicy() {
    }

    public static boolean appliesAt(Level level, BlockPos position) {
        SubLevel containing = Sable.HELPER.getContaining(level, position);
        return DetachedMiniPhysicsSubLevelService.usesAntikytheraHalfScalePolicy(containing);
    }

    /**
     * Bucket placement/waterlogging is unrestricted outside Antikythera mini worlds. Inside either
     * Antikythera sublevel type the contained fluid must resolve through the normal mini whitelist.
     */
    public static boolean allowsBucketFluid(Level level, BlockPos position, Fluid fluid) {
        return !appliesAt(level, position) || MiniaturizableRegistry.isAllowed(fluid);
    }
}
