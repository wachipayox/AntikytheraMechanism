package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateMiniKineticLifecycle;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Applies the existing Create kinetic cut to successful Sable/host relocation journals. */
@Mixin(SableFrameRelocationService.class)
abstract class SableFrameRelocationKineticsMixin {
    @Inject(method = "prepareRelocationJournals", at = @At("RETURN"), remap = false)
    private static void antikytheramechanism$cutKineticsAfterJournalCommit(
            ServerLevel level,
            AssemblyTransform transform,
            List<BlockPos> movedBlocks,
            CallbackInfoReturnable<Boolean> callback) {
        if (!Boolean.TRUE.equals(callback.getReturnValue())) {
            return;
        }

        Set<BlockPos> operationSources = movedBlocks.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<UUID> movingIds = manager.assemblies().stream()
                .filter(assembly -> manager.pendingContraptionMove(assembly.id()).isPresent())
                .filter(assembly -> operationSources.containsAll(assembly.frames()))
                .map(MechanismAssembly::id)
                .toList();
        if (!movingIds.isEmpty()) {
            // At this point both source and destination journals exist but Sable has not copied the
            // first block yet. This is the same safe cut boundary used by Create contraption capture.
            CreateMiniKineticLifecycle.disconnectContraptionCapture(level, movingIds);
        }
    }
}
