package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.ContraptionSourceRelease;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.assembly.PendingContraptionMoveReleaseAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Adds backward-compatible source-release metadata to Create's existing persistent move journal. */
@Mixin(value = PendingContraptionMove.class, remap = false)
abstract class PendingContraptionMoveSourceReleaseMixin implements PendingContraptionMoveReleaseAccess {
    @Unique
    private Set<BlockPos> antikytheramechanism$releasedSourceFrames = Set.of();

    @Override
    public Set<BlockPos> antikytheramechanism$getReleasedSourceFrames() {
        return antikytheramechanism$releasedSourceFrames;
    }

    @Override
    public void antikytheramechanism$setReleasedSourceFrames(Collection<BlockPos> frames) {
        LinkedHashSet<BlockPos> copied = new LinkedHashSet<>();
        for (BlockPos frame : frames) {
            copied.add(java.util.Objects.requireNonNull(frame, "released source Frame").immutable());
        }
        antikytheramechanism$releasedSourceFrames = Set.copyOf(copied);
    }

    @Inject(method = "withPlacement", at = @At("RETURN"), remap = false)
    private void antikytheramechanism$copyReleasedSourcesToPlacedJournal(
            Collection<BlockPos> targetFrames,
            BlockPos targetOrigin,
            dev.antikytheramechanism.assembly.AssemblyPose finalPose,
            CallbackInfoReturnable<PendingContraptionMove> callback) {
        ContraptionSourceRelease.setReleasedSources(
                callback.getReturnValue(), antikytheramechanism$releasedSourceFrames);
    }

    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void antikytheramechanism$saveReleasedSources(
            CallbackInfoReturnable<CompoundTag> callback) {
        if (antikytheramechanism$releasedSourceFrames.isEmpty()) {
            return;
        }
        long[] packed = antikytheramechanism$releasedSourceFrames.stream()
                .mapToLong(BlockPos::asLong)
                .sorted()
                .toArray();
        callback.getReturnValue().putLongArray(
                ContraptionSourceRelease.RELEASED_SOURCE_FRAMES_TAG, packed);
    }

    @Inject(method = "loadDecoded", at = @At("RETURN"), remap = false)
    private static void antikytheramechanism$loadReleasedSources(
            CompoundTag tag,
            Map<BlockPos, BlockState> carriedBoundaryBlocks,
            CallbackInfoReturnable<PendingContraptionMove> callback) {
        Set<BlockPos> released = ContraptionSourceRelease.decodeReleasedSources(tag);
        ContraptionSourceRelease.setReleasedSources(callback.getReturnValue(), released);
    }
}
