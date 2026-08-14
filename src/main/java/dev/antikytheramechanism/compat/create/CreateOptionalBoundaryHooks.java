package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.transmission.TransmissionLinkCoordinator;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.Collection;
import java.util.UUID;

/**
 * Optional Create-only boundary work behind a class-loading barrier.
 *
 * <p>The outer methods contain no Create type descriptors. The nested implementation is touched
 * only when Create is actually loaded, so crash recovery can safely call these hooks in worlds
 * started without Create.</p>
 */
final class CreateOptionalBoundaryHooks {
    private CreateOptionalBoundaryHooks() {}

    static void quiesce(ServerLevel level, Collection<UUID> ids) {
        if (ModList.get().isLoaded("create")) Loaded.quiesce(level, ids);
    }

    static void rebuild(ServerLevel level, Collection<UUID> ids) {
        if (ModList.get().isLoaded("create")) Loaded.rebuild(level, ids);
    }

    private static final class Loaded {
        private static void quiesce(ServerLevel level, Collection<UUID> ids) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
            for (UUID id : ids) {
                manager.getAssembly(id).ifPresent(assembly ->
                        TransmissionLinkCoordinator.quiesceAssembly(level, assembly));
            }
        }

        private static void rebuild(ServerLevel level, Collection<UUID> ids) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
            for (UUID id : ids) {
                manager.getAssembly(id).ifPresent(assembly ->
                        TransmissionLinkCoordinator.rebuildAssemblyKinetics(level, assembly));
            }
        }
    }
}
