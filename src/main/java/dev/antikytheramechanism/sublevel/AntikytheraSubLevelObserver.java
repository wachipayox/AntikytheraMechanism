package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** Reconciles Sable lifecycle events with assembly SavedData. */
public final class AntikytheraSubLevelObserver implements SubLevelObserver {
    private final ServerLevel level;

    private AntikytheraSubLevelObserver(ServerLevel level) {
        this.level = level;
    }

    public static void onContainerReady(ForgeSableSubLevelContainerReadyEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            event.getContainer().addObserver(new AntikytheraSubLevelObserver(serverLevel));
        }
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        // No host MassTracker mutation is required on managed-child load. Sable rebuilds the child's
        // authoritative MassData from disk and HostedMiniMassBridge projects it into the foreign host
        // on the next merged-mass update.
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel);
        if (ownerId == null) {
            return;
        }
        // UNLOADED is Sable's normal save/shutdown lifecycle and deliberately keeps
        // occupancy data. The persistent UUID must survive it so the same body loads
        // from its force-load ticket on the next start.
        if (reason == SubLevelRemovalReason.UNLOADED) {
            AntikytheraMechanism.LOGGER.debug(
                    "Managed Sable SubLevel {} for assembly {} unloaded normally",
                    subLevel.getUniqueId(),
                    ownerId);
            return;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        manager.getAssembly(ownerId).ifPresent(assembly -> {
            if (subLevel.getUniqueId().equals(assembly.subLevelId())) {
                assembly.setSubLevelId(null);
                manager.setDirty();
            }
        });
        AntikytheraMechanism.LOGGER.warn(
                "Managed Sable SubLevel {} for assembly {} was removed ({})",
                subLevel.getUniqueId(),
                ownerId,
                reason);
    }
}
