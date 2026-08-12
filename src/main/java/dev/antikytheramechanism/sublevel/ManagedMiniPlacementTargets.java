package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Preflight for placement helpers that choose a target independently from BlockPlaceContext. */
public final class ManagedMiniPlacementTargets {
    private ManagedMiniPlacementTargets() {
    }

    /** True when {@code source} is a user-facing position inside an Antikythera SubLevel. */
    public static boolean isManagedSource(Level level, BlockPos source) {
        return MiniWorldEnvironment.isManagedSubLevel(Sable.HELPER.getContaining(level, source));
    }

    /**
     * Validates a helper-selected target relative to the managed SubLevel containing {@code source}.
     * The target does not need to be inside an already allocated plot chunk; ownership is derived
     * from the assembly FrameMask instead of Sable#getContaining(target).
     */
    public static boolean isOwnedTarget(ServerLevel level, BlockPos source, BlockPos target) {
        SubLevel containing = Sable.HELPER.getContaining(level, source);
        if (!(containing instanceof ServerSubLevel subLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return true;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssembly(ownerId)
                .orElse(null);
        if (assembly == null) {
            return false;
        }

        BlockPos miniTarget = target.subtract(subLevel.getPlot().getCenterBlock());
        return MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniTarget);
    }
}
