package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Keeps an empty managed Sable SubLevel alive without inventing physical or synthetic volume.
 *
 * <p>An empty mechanism has no mini block that needs collision, raycast or placement broadphase.
 * Its parent Mechanism Frames already provide the world-space interaction surface used to place the
 * first mini block. Leaving Sable's plot bounds genuinely empty avoids a second invisible volume
 * that can interfere with normal parent-world placement. As soon as a real mini block is written,
 * Sable's ordinary plot bookkeeping creates physical bounds from that content.</p>
 */
public final class ManagedSubLevelBounds {
    private ManagedSubLevelBounds() {
    }

    /**
     * @return true when Sable's normal empty-plot removal must be cancelled for this managed
     * assembly. The plot bounds are intentionally left as {@link BoundingBox3i#EMPTY}.
     */
    public static boolean preserveIfEmpty(ServerSubLevel subLevel) {
        BoundingBox3ic current = subLevel.getPlot().getBoundingBox();
        if (current != BoundingBox3i.EMPTY && current.volume() > 0) {
            return false;
        }
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return false;
        }
        if (!(subLevel.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        return assembly != null && !assembly.frames().isEmpty();
    }
}
