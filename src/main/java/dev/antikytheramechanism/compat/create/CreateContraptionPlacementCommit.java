package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.CreatePlacementCommitService;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

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
    }
}
