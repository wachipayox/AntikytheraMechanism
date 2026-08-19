package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.assembly.ContraptionSourceRelease;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Persistent Create source-release semantics layered onto the assembly manager transaction. */
@Mixin(MechanismAssemblyManager.class)
public abstract class MechanismAssemblyManagerVacatedSourceMixin {

    @Inject(method = "isContraptionLifecycleTransition", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$excludeReleasedCreateSourcesFromActiveTransition(
            BlockPos framePosition,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        callbackInfo.setReturnValue(ContraptionSourceRelease.isActiveTransition(
                (MechanismAssemblyManager) (Object) this,
                framePosition));
    }

    /**
     * A replacement Frame can make every historical source coordinate contain a Frame again. Once
     * Create has released any source, that can no longer prove that extraction never started.
     */
    @WrapOperation(
            method = "reconcilePendingContraptionMoves",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/antikytheramechanism/assembly/PendingContraptionMove;hasPlacement()Z",
                    ordinal = 0))
    private boolean antikytheramechanism$keepStartedReleasedJournal(
            PendingContraptionMove move,
            Operation<Boolean> original) {
        return original.call(move) || !move.releasedSourceFrames().isEmpty();
    }

    /**
     * Finalization still validates the historical source mapping, but a released source has no live
     * frameIndex owner by design. Supply a unique historical UUID to this validation read only;
     * target collision reads continue to see the real replacement owner. Ambiguous historical
     * ownership remains fail-closed.
     */
    @WrapOperation(
            method = "finalizeContraptionPlacement",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 1))
    private Object antikytheramechanism$validateReleasedHistoricalSourceOwner(
            Map<?, ?> map,
            Object key,
            Operation<Object> original) {
        Object actual = original.call(map, key);
        return ContraptionSourceRelease.releasedHistoricalOwner(
                (MechanismAssemblyManager) (Object) this,
                map,
                key,
                actual);
    }

    /** Rollback code predates source reuse and may restore the moving UUID over a replacement. */
    @Inject(method = "finalizeContraptionPlacement", at = @At("RETURN"))
    private void antikytheramechanism$repairReleasedOwnersAfterFailedFinalize(
            ServerLevel level,
            Collection<UUID> assemblyIds,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!callbackInfo.getReturnValue()) {
            ContraptionSourceRelease.repairReleasedFrameIndex((MechanismAssemblyManager) (Object) this);
        }
    }

    /**
     * SavedData historically rebuilds frameIndex before it decodes Create journals. Repair released
     * coordinates after decoding so save order cannot resurrect the moving historical owner.
     */
    @Inject(method = "load", at = @At("RETURN"))
    private static void antikytheramechanism$repairReleasedOwnersAfterLoad(
            CompoundTag tag,
            HolderLookup.Provider registries,
            CallbackInfoReturnable<MechanismAssemblyManager> callbackInfo) {
        ContraptionSourceRelease.repairReleasedFrameIndex(callbackInfo.getReturnValue());
    }
}
