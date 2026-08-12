package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Sable normally treats a zero-mass SubLevel as dead and removes it. That rule does not apply to
 * Antikythera assemblies: their lifetime is owned by the parent Mechanism Frames and their pose is
 * driven externally, so an empty frame or a mechanism made only from non-massive blocks is valid.
 */
public final class ManagedSubLevelMassPolicy {
    private ManagedSubLevelMassPolicy() {
    }

    public static boolean mayRemainMassless(ServerSubLevel subLevel) {
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)
                || !(subLevel.getLevel() instanceof ServerLevel level)) {
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
