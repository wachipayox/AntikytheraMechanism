package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CreateContraptionLifecycle {
    private CreateContraptionLifecycle() {}

    public static boolean preflight(Contraption contraption, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return true;
        CreateFrameCapture.Captures captures = CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) return true;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        boolean upright = captures.localFramesByAssembly().keySet().stream().map(manager::getAssembly)
                .allMatch(assembly -> assembly.isPresent() && assembly.get().orientation().isUpright());
        boolean allowed = upright && !captures.missingAssemblyId()
                && manager.canCaptureContraption(serverLevel, captures.localFramesByAssembly(), true);
        if (!allowed) AntikytheraMechanism.LOGGER.warn(
                "Rejected Create contraption capture: Mechanism Frame assemblies must be complete, healthy, unlocked and upright");
        return allowed;
    }

    public static boolean beginRemoval(Contraption contraption, Level level, BlockPos removalOffset) {
        if (!(level instanceof ServerLevel serverLevel)) return true;
        CreateFrameCapture.Captures captures = CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) return true;
        if (captures.missingAssemblyId() || contraption.anchor == null
                || !(contraption instanceof CreateContraptionAnchorAccess anchorAccess)) return false;
        BlockPos sourceTranslation = contraption.anchor.offset(removalOffset);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);

        Map<UUID, Map<BlockPos, BlockState>> boundaryBlocks = new HashMap<>();
        captures.carriedBoundaryBlocksByAssembly().forEach((id, states) ->
                boundaryBlocks.put(id, new HashMap<>(states)));

        // Create intentionally excludes the block that creates/anchors a contraption from getBlocks().
        // For a bearing, for example, anchor is the first moved block above the bearing while
        // isAnchoringBlockAt(anchor.below()) identifies the stationary bearing itself. The previous
        // implementation sampled sourceTranslation (local ZERO), which is the moved block/Frame in
        // exactly that setup, so the real controller never entered the structural boundary snapshot.
        //
        // Scan only faces actually touched by a captured Frame and ask Create's own anchoring predicate
        // which excluded neighbour is the controller. Store it in the same local coordinate system as
        // getBlocks(); PendingContraptionMove can then project it back to the correct source position.
        for (Map.Entry<UUID, Set<BlockPos>> capture : captures.localFramesByAssembly().entrySet()) {
            Map<BlockPos, BlockState> adjacent = boundaryBlocks.computeIfAbsent(
                    capture.getKey(), ignored -> new HashMap<>());
            for (BlockPos localFrame : capture.getValue()) {
                for (Direction direction : Direction.values()) {
                    BlockPos localNeighbor = localFrame.relative(direction);
                    if (contraption.getBlocks().containsKey(localNeighbor)) continue;

                    BlockPos originalNeighbor = contraption.anchor.offset(localNeighbor);
                    if (!anchorAccess.antikytheramechanism$isAnchoringBlockAt(originalNeighbor)) continue;

                    BlockPos sourceNeighbor = sourceTranslation.offset(localNeighbor);
                    if (!serverLevel.hasChunkAt(sourceNeighbor)) continue;
                    BlockState state = serverLevel.getBlockState(sourceNeighbor);
                    if (!state.isAir() && !state.is(ModRegistries.MECHANISM_FRAME.get())) {
                        adjacent.putIfAbsent(localNeighbor.immutable(), state);
                    }
                }
            }
        }

        boolean journaled = manager.prepareContraptionMoves(
                serverLevel,
                captures.localFramesByAssembly(),
                boundaryBlocks,
                sourceTranslation,
                true);
        if (journaled) {
            CreateContraptionBoundaryLifecycle.disconnect(serverLevel, captures.localFramesByAssembly().keySet());
        }
        return journaled;
    }

    public static boolean beginPlacement(Contraption contraption, Level level, StructureTransform transform) {
        if (!(level instanceof ServerLevel serverLevel) || contraption.disassembled) return true;
        CreateFrameCapture.Captures captures = CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) return true;
        if (captures.missingAssemblyId() || transform.mirror != null && transform.mirror != Mirror.NONE) return false;
        if (transform.rotationAxis != null && transform.rotationAxis != Direction.Axis.Y && transform.angle != 0) return false;

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        Map<UUID, Set<BlockPos>> targets = new HashMap<>();
        Map<UUID, BlockPos> targetOrigins = new HashMap<>();
        Map<UUID, AssemblyPose> finalPoses = new HashMap<>();
        Quaterniond snappedRotation = snappedRotation(transform);

        for (Map.Entry<UUID, Set<BlockPos>> entry : captures.localFramesByAssembly().entrySet()) {
            PendingContraptionMove move = manager.pendingContraptionMove(entry.getKey()).orElse(null);
            if (move == null || move.hasPlacement() || !move.localFrames().equals(entry.getValue())) return false;
            BlockPos sourceTranslation = PendingContraptionMove.findTranslation(move.localFrames(), move.sourceFrames()).orElse(null);
            if (sourceTranslation == null) return false;
            BlockPos localOrigin = move.sourceOrigin().subtract(sourceTranslation);
            BlockPos targetOrigin = transform.apply(localOrigin);
            Set<BlockPos> targetFrames = entry.getValue().stream().map(transform::apply).map(BlockPos::immutable)
                    .collect(Collectors.toUnmodifiableSet());
            Quaterniond finalOrientation = new Quaterniond(snappedRotation)
                    .mul(move.startPose().orientation(new Quaterniond())).normalize();
            FrameOrientation discrete = FrameOrientation.fromQuaternion(finalOrientation).orElse(null);
            if (discrete == null || !discrete.isUpright()) return false;
            targets.put(entry.getKey(), targetFrames);
            targetOrigins.put(entry.getKey(), targetOrigin);
            finalPoses.put(entry.getKey(), new AssemblyPose(
                    targetOrigin.getX() + .5, targetOrigin.getY() + .5, targetOrigin.getZ() + .5,
                    finalOrientation.x, finalOrientation.y, finalOrientation.z, finalOrientation.w));
        }
        return manager.prepareContraptionPlacement(serverLevel, targets, targetOrigins, finalPoses);
    }

    public static void finishPlacement(Contraption contraption, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        CreateFrameCapture.Captures captures = CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.localFramesByAssembly().isEmpty()) return;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        Set<UUID> ids = captures.localFramesByAssembly().keySet();
        boolean allPrepared = ids.stream().map(manager::pendingContraptionMove)
                .allMatch(move -> move.isPresent() && move.get().hasPlacement());
        if (!allPrepared) return;
        if (!manager.finalizeContraptionPlacement(serverLevel, ids)) {
            AntikytheraMechanism.LOGGER.error(
                    "Create placed Mechanism Frames but their assembly metadata could not commit; persistent journals were retained for recovery");
        }
    }

    private static Quaterniond snappedRotation(StructureTransform transform) {
        if (transform.rotationAxis == null || transform.angle == 0) return new Quaterniond();
        Direction.Axis axis = transform.rotationAxis;
        return new Quaterniond().rotateAxis(Math.toRadians(transform.angle),
                axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
    }
}
