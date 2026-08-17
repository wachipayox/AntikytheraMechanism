package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.CreatePlacementCommitService;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Bridges Create's synchronous placement return into the durable assembly commit. */
public final class CreateContraptionPlacementCommit {
    private CreateContraptionPlacementCommit() {
    }

    public static void finishPlacement(Contraption contraption, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        Set<UUID> ids = captures.localFramesByAssembly().keySet();
        if (ids.isEmpty()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        boolean allPrepared = ids.stream()
                .map(manager::pendingContraptionMove)
                .allMatch(move -> move.isPresent() && move.get().hasPlacement());
        if (!allPrepared) {
            return;
        }

        CreatePlacementCommitService.CommitResult result =
                CreatePlacementCommitService.finalizePreparedPlacement(serverLevel, ids);
        if (!result.committed()) {
            AntikytheraMechanism.LOGGER.error(
                    "Create placed Mechanism Frames but their assembly metadata could not commit; persistent journals were retained for recovery");
            return;
        }
        if (!result.assembliesToReconnect().isEmpty()) {
            CreateContraptionBoundaryLifecycle.reconnect(serverLevel, result.assembliesToReconnect());
        }

        /*
         * Capture-side quiescing deliberately hides cross-assembly virtual kinetic edges while a
         * Frame is moving. Once placement has committed, the topology can have changed in either
         * direction: the moved assembly may have arrived beside an existing source/network, an
         * existing network may now reach into the moved assembly, or the placed assembly may be the
         * new bridge between two previously separate networks. Re-advertise the whole same-host
         * cohort only after structural ownership and reconnect have settled so Create discovers all
         * newly legal edges from both sides with its normal propagation rules.
         *
         * Exceptional placement can split/rehome the original IDs, so include the commit's live
         * reconnect set as seeds as well as the IDs captured by the contraption.
         */
        LinkedHashSet<UUID> kineticSeeds = new LinkedHashSet<>(ids);
        kineticSeeds.addAll(result.assembliesToReconnect());
        CreateMiniKineticLifecycle.scheduleAfterContraptionPlacement(serverLevel, kineticSeeds);
    }
}
