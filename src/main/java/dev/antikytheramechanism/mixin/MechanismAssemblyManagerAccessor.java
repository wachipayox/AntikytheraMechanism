package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.PendingFrameEvacuation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Narrow transaction access used only to reconcile Create's legitimate partial placement result. */
@Mixin(MechanismAssemblyManager.class)
public interface MechanismAssemblyManagerAccessor {
    @Accessor(value = "assemblies", remap = false)
    Map<UUID, MechanismAssembly> antikytheramechanism$getAssemblies();

    @Accessor(value = "frameIndex", remap = false)
    Map<BlockPos, UUID> antikytheramechanism$getFrameIndex();

    @Accessor(value = "pendingContraptionMoves", remap = false)
    Map<UUID, PendingContraptionMove> antikytheramechanism$getPendingContraptionMoves();

    @Accessor(value = "pendingFrameEvacuations", remap = false)
    Map<UUID, PendingFrameEvacuation> antikytheramechanism$getPendingFrameEvacuations();

    @Accessor(value = "contentRecoveryLocks", remap = false)
    Set<UUID> antikytheramechanism$getContentRecoveryLocks();

    @Accessor(value = "invalidContraptionMovesLogged", remap = false)
    Set<UUID> antikytheramechanism$getInvalidContraptionMovesLogged();

    @Invoker(value = "splitDisconnectedAssembly", remap = false)
    void antikytheramechanism$splitDisconnectedAssembly(ServerLevel level, MechanismAssembly source);
}
