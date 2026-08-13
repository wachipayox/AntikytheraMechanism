package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyManagerContraptionBoundaryRecoveryMixin {
    @Shadow @Final private Map<UUID, PendingContraptionMove> pendingContraptionMoves;
    @Unique private final Set<UUID> antikytheramechanism$abortedBoundaryCandidates = new HashSet<>();

    @Inject(method = "reconcilePendingContraptionMoves", at = @At("HEAD"))
    private void antikytheramechanism$captureAborted(ServerLevel level, CallbackInfo ci) {
        antikytheramechanism$abortedBoundaryCandidates.clear();
        for (PendingContraptionMove move : pendingContraptionMoves.values()) {
            if (!move.hasPlacement()
                    && move.sourceFrames().stream().allMatch(level::hasChunkAt)
                    && move.sourceFrames().stream().allMatch(position ->
                    level.getBlockState(position).is(ModRegistries.MECHANISM_FRAME.get()))) {
                antikytheramechanism$abortedBoundaryCandidates.add(move.assemblyId());
            }
        }
    }

    @Inject(method = "reconcilePendingContraptionMoves", at = @At("RETURN"))
    private void antikytheramechanism$reconnectAborted(ServerLevel level, CallbackInfo ci) {
        if (antikytheramechanism$abortedBoundaryCandidates.isEmpty()) return;
        Set<UUID> restored = new HashSet<>();
        for (UUID id : antikytheramechanism$abortedBoundaryCandidates) {
            if (!pendingContraptionMoves.containsKey(id)) restored.add(id);
        }
        antikytheramechanism$abortedBoundaryCandidates.clear();
        if (!restored.isEmpty()) CreateContraptionBoundaryLifecycle.reconnect(level, restored);
    }
}
