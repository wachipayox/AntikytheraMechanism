package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Preflight for placement paths that choose targets from an already-managed mini block. */
public final class ManagedMiniPlacementTargets {
    private ManagedMiniPlacementTargets() {
    }

    /** True when {@code source} is a user-facing position inside an Antikythera SubLevel. */
    public static boolean isManagedSource(Level level, BlockPos source) {
        return MiniWorldEnvironment.isManagedSubLevel(Sable.HELPER.getContaining(level, source));
    }

    /**
     * Validates a placement target relative to the managed SubLevel containing {@code source}.
     *
     * <p>Server-side ownership is checked against the authoritative FrameMask. The client does not
     * own that saved-data graph, so it projects the target cell centre back into the parent world
     * and requires that it land inside a real Mechanism Frame. This lets invalid outside placements
     * be rejected before the client shows a one-frame ghost or sends a speculative use packet.</p>
     */
    public static boolean isOwnedTarget(Level level, BlockPos source, BlockPos target) {
        SubLevel containing = Sable.HELPER.getContaining(level, source);
        if (containing == null || !MiniWorldEnvironment.isManagedSubLevel(containing)) {
            return true;
        }

        if (level instanceof ServerLevel serverLevel && containing instanceof ServerSubLevel serverSubLevel) {
            return isOwnedServerTarget(serverLevel, serverSubLevel, target);
        }

        Vec3 worldTarget = containing.logicalPose().transformPosition(Vec3.atCenterOf(target));
        BlockPos parentTarget = BlockPos.containing(worldTarget);
        return level.getBlockState(parentTarget).is(ModRegistries.MECHANISM_FRAME.get());
    }

    private static boolean isOwnedServerTarget(
            ServerLevel level,
            ServerSubLevel subLevel,
            BlockPos target) {
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
