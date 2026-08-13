package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyManagerContraptionRotationMixin {
    @Shadow @Final private Map<UUID, MechanismAssembly> assemblies;
    @Shadow @Final private Map<BlockPos, UUID> frameIndex;
    @Shadow @Final private Map<UUID, PendingContraptionMove> pendingContraptionMoves;

    @Inject(method = "finalizeContraptionPlacement", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$commitRotatedMapping(
            ServerLevel level, Collection<UUID> assemblyIds, CallbackInfoReturnable<Boolean> callback) {
        ArrayList<PendingContraptionMove> moves = new ArrayList<>();
        boolean needsRotatedCommit = false;
        for (UUID id : assemblyIds) {
            PendingContraptionMove move = pendingContraptionMoves.get(id);
            MechanismAssembly assembly = assemblies.get(id);
            if (move == null || assembly == null || !move.hasPlacement()) return;
            FrameOrientation target = orientation(move.finalPose());
            if (target == null) {
                callback.setReturnValue(false);
                return;
            }
            needsRotatedCommit |= !target.equals(assembly.orientation());
            moves.add(move);
        }
        if (!needsRotatedCommit) return;

        Map<UUID, Snapshot> snapshots = new HashMap<>();
        for (PendingContraptionMove move : moves) {
            MechanismAssembly assembly = assemblies.get(move.assemblyId());
            FrameOrientation targetOrientation = orientation(move.finalPose());
            if (targetOrientation == null || !targetOrientation.isUpright()
                    || !assembly.frames().equals(move.sourceFrames())) {
                callback.setReturnValue(false);
                return;
            }
            for (BlockPos source : move.sourceFrames()) {
                if (!assembly.id().equals(frameIndex.get(source))) {
                    callback.setReturnValue(false);
                    return;
                }
                BlockPos logical = assembly.logicalFrameOffset(source);
                BlockPos expected = move.targetOrigin().offset(targetOrientation.toPhysical(logical));
                if (!move.targetFrames().contains(expected)) {
                    callback.setReturnValue(false);
                    return;
                }
            }
            for (BlockPos target : move.targetFrames()) {
                if (!level.hasChunkAt(target)
                        || !level.getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())
                        || !(level.getBlockEntity(target) instanceof MechanismFrameBlockEntity)) {
                    callback.setReturnValue(false);
                    return;
                }
                UUID owner = frameIndex.get(target);
                if (owner != null && !owner.equals(assembly.id())
                        && moves.stream().noneMatch(other -> other.assemblyId().equals(owner))) {
                    callback.setReturnValue(false);
                    return;
                }
            }
            snapshots.put(assembly.id(), new Snapshot(
                    assembly.origin(), Set.copyOf(assembly.frames()), assembly.orientation(), assembly.poseTarget()));
        }

        try {
            for (PendingContraptionMove move : moves) {
                move.sourceFrames().forEach(source -> {
                    if (move.assemblyId().equals(frameIndex.get(source))) frameIndex.remove(source);
                });
            }
            for (PendingContraptionMove move : moves) {
                MechanismAssembly assembly = assemblies.get(move.assemblyId());
                FrameOrientation target = orientation(move.finalPose());
                assembly.relocate(move.targetOrigin(), move.targetFrames(), target);
                assembly.setPoseTarget(move.finalPose());
                move.targetFrames().forEach(position -> frameIndex.put(position, assembly.id()));
            }

            MechanismAssemblyManager manager = (MechanismAssemblyManager) (Object) this;
            for (PendingContraptionMove move : moves) {
                MechanismAssembly assembly = assemblies.get(move.assemblyId());
                for (BlockPos frame : move.targetFrames()) {
                    if (level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity blockEntity) {
                        blockEntity.setAssemblyMapping(
                                assembly.id(), assembly.orientation(), assembly.logicalFrameOffset(frame));
                    }
                    manager.refreshFrame(level, frame);
                }
                pendingContraptionMoves.remove(move.assemblyId());
            }
            manager.setDirty();
            callback.setReturnValue(true);
        } catch (RuntimeException exception) {
            for (PendingContraptionMove move : moves) {
                move.targetFrames().forEach(target -> {
                    if (move.assemblyId().equals(frameIndex.get(target))) frameIndex.remove(target);
                });
            }
            for (Map.Entry<UUID, Snapshot> entry : snapshots.entrySet()) {
                MechanismAssembly assembly = assemblies.get(entry.getKey());
                Snapshot old = entry.getValue();
                assembly.relocate(old.origin(), old.frames(), old.orientation());
                assembly.setPoseTarget(old.pose());
                old.frames().forEach(frame -> frameIndex.put(frame, assembly.id()));
            }
            ((MechanismAssemblyManager) (Object) this).setDirty();
            AntikytheraMechanism.LOGGER.error(
                    "Could not commit rotated Create placement; persistent journals were retained", exception);
            callback.setReturnValue(false);
        }
    }

    @Unique
    private static FrameOrientation orientation(AssemblyPose pose) {
        return FrameOrientation.fromQuaternion(pose.orientation(new Quaterniond())).orElse(null);
    }

    @Unique
    private record Snapshot(BlockPos origin, Set<BlockPos> frames,
                            FrameOrientation orientation, AssemblyPose pose) {}
}
