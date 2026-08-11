package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.TranslatingContraption;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Transaction boundary called by the optional Create mixin. */
public final class CreateContraptionLifecycle {
    private CreateContraptionLifecycle() {
    }

    public static boolean preflight(Contraption contraption, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) {
            return true;
        }
        boolean translationOnly = contraption instanceof TranslatingContraption;
        boolean allowed = !captures.missingAssemblyId()
                && MechanismAssemblyManager.get(serverLevel).canCaptureContraption(
                        serverLevel,
                        captures.localFramesByAssembly(),
                        translationOnly);
        if (!allowed) {
            AntikytheraMechanism.LOGGER.warn(
                    "Rejected Create contraption capture: every Mechanism Frame assembly must be complete, healthy and unlocked; rotating contraptions currently support one-frame assemblies only");
        }
        return allowed;
    }

    public static boolean beginRemoval(Contraption contraption, Level level, BlockPos removalOffset) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) {
            return true;
        }
        if (captures.missingAssemblyId() || contraption.anchor == null) {
            return false;
        }
        BlockPos sourceTranslation = contraption.anchor.offset(removalOffset);
        return MechanismAssemblyManager.get(serverLevel).prepareContraptionMoves(
                serverLevel,
                captures.localFramesByAssembly(),
                sourceTranslation,
                contraption instanceof TranslatingContraption);
    }

    public static boolean beginPlacement(
            Contraption contraption,
            Level level,
            StructureTransform transform) {
        if (!(level instanceof ServerLevel serverLevel) || contraption.disassembled) {
            return true;
        }
        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) {
            return true;
        }
        if (captures.missingAssemblyId()
                || transform.mirror != null && transform.mirror != Mirror.NONE) {
            return false;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        Map<UUID, Set<BlockPos>> targets = new HashMap<>();
        Map<UUID, BlockPos> targetOrigins = new HashMap<>();
        Map<UUID, AssemblyPose> finalPoses = new HashMap<>();
        Quaterniond snappedRotation = snappedRotation(transform);

        for (Map.Entry<UUID, Set<BlockPos>> entry : captures.localFramesByAssembly().entrySet()) {
            PendingContraptionMove move = manager.pendingContraptionMove(entry.getKey()).orElse(null);
            if (move == null || move.hasPlacement() || !move.localFrames().equals(entry.getValue())) {
                return false;
            }
            BlockPos sourceTranslation = PendingContraptionMove.findTranslation(
                            move.localFrames(), move.sourceFrames())
                    .orElse(null);
            if (sourceTranslation == null) {
                return false;
            }
            BlockPos localOrigin = move.sourceOrigin().subtract(sourceTranslation);
            BlockPos targetOrigin = transform.apply(localOrigin);
            Set<BlockPos> targetFrames = entry.getValue().stream()
                    .map(transform::apply)
                    .map(BlockPos::immutable)
                    .collect(Collectors.toUnmodifiableSet());

            Quaterniond finalOrientation = new Quaterniond(snappedRotation)
                    .mul(move.startPose().orientation(new Quaterniond()))
                    .normalize();
            targets.put(entry.getKey(), targetFrames);
            targetOrigins.put(entry.getKey(), targetOrigin);
            finalPoses.put(entry.getKey(), new AssemblyPose(
                    targetOrigin.getX() + 0.5,
                    targetOrigin.getY() + 0.5,
                    targetOrigin.getZ() + 0.5,
                    finalOrientation.x,
                    finalOrientation.y,
                    finalOrientation.z,
                    finalOrientation.w));
        }
        return manager.prepareContraptionPlacement(serverLevel, targets, targetOrigins, finalPoses);
    }

    public static void finishPlacement(Contraption contraption, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.localFramesByAssembly().isEmpty()) {
            return;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        boolean allPrepared = captures.localFramesByAssembly().keySet().stream()
                .map(manager::pendingContraptionMove)
                .allMatch(move -> move.isPresent() && move.get().hasPlacement());
        if (allPrepared
                && !manager.finalizeContraptionPlacement(
                        serverLevel, captures.localFramesByAssembly().keySet())) {
            AntikytheraMechanism.LOGGER.error(
                    "Create placed Mechanism Frames but their assembly metadata could not commit; persistent journals were retained for recovery");
        }
    }

    private static Quaterniond snappedRotation(StructureTransform transform) {
        if (transform.rotationAxis == null || transform.angle == 0) {
            return new Quaterniond();
        }
        Direction.Axis axis = transform.rotationAxis;
        return new Quaterniond().rotateAxis(
                Math.toRadians(transform.angle),
                axis == Direction.Axis.X ? 1.0 : 0.0,
                axis == Direction.Axis.Y ? 1.0 : 0.0,
                axis == Direction.Axis.Z ? 1.0 : 0.0);
    }
}
