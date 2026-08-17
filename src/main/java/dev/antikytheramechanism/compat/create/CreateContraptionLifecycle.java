package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
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
        boolean allowed = !captures.missingAssemblyId()
                && manager.canCaptureContraption(serverLevel, captures.localFramesByAssembly(), true);
        if (!allowed) AntikytheraMechanism.LOGGER.warn(
                "Rejected Create contraption capture: Mechanism Frame assemblies must be complete, healthy and unlocked");
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
            Set<UUID> movingIds = captures.localFramesByAssembly().keySet();
            CreateContraptionBoundaryLifecycle.disconnect(serverLevel, movingIds);
            // A pending move already suppresses future virtual neighbours, but an edge that Create
            // attached before capture remains part of its KineticNetwork until explicitly rebuilt.
            // Rebuild the complete same-host cohort now: internal mini networks are preserved while
            // every virtual edge touching the moving assembly is forced to disappear before removal.
            CreateMiniKineticLifecycle.disconnectContraptionCapture(serverLevel, movingIds);
        }
        return journaled;
    }

    public static boolean beginPlacement(Contraption contraption, Level level, StructureTransform transform) {
        if (!(level instanceof ServerLevel serverLevel) || contraption.disassembled) return true;
        CreateFrameCapture.Captures captures = CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.isEmpty()) return true;
        if (captures.missingAssemblyId() || transform.mirror != null && transform.mirror != Mirror.NONE) return false;

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
            if (discrete == null) return false;
            targets.put(entry.getKey(), targetFrames);
            targetOrigins.put(entry.getKey(), targetOrigin);
            finalPoses.put(entry.getKey(), new AssemblyPose(
                    targetOrigin.getX() + .5, targetOrigin.getY() + .5, targetOrigin.getZ() + .5,
                    finalOrientation.x, finalOrientation.y, finalOrientation.z, finalOrientation.w));
        }

        /*
         * MechanismAssemblyManager normally refuses to journal a target already owned by an unrelated
         * Frame assembly. Create, however, treats that Frame like any other replaceable destination
         * block and will destroy it before placing the carried Frame. Keep that normal Create semantic
         * without losing Antikythera content: validate the displaced Frame now, temporarily hide only
         * its index entry while the placement journal is created, then restore the old ownership. The
         * post-placement commit can infer the still-indexed displaced owner after Create has actually
         * replaced the outer block and evacuate/split its mini assembly transactionally.
         */
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Set<UUID> movingIds = targets.keySet();
        Map<BlockPos, UUID> displacedOwners = new HashMap<>();
        for (Set<BlockPos> targetSet : targets.values()) {
            for (BlockPos target : targetSet) {
                UUID owner = frameIndex.get(target);
                if (owner == null || movingIds.contains(owner)) {
                    continue;
                }
                MechanismAssembly displaced = manager.getAssembly(owner).orElse(null);
                if (displaced == null
                        || !displaced.containsFrame(target)
                        || manager.pendingPistonMove(owner).isPresent()
                        || manager.pendingContraptionMove(owner).isPresent()
                        || manager.isContentRecoveryLocked(owner)
                        || !serverLevel.getBlockState(target).is(ModRegistries.MECHANISM_FRAME.get())
                        || !(serverLevel.getBlockEntity(target) instanceof MechanismFrameBlockEntity frame)
                        || !owner.equals(frame.getAssemblyId())) {
                    return false;
                }
                displacedOwners.put(target.immutable(), owner);
            }
        }

        displacedOwners.keySet().forEach(frameIndex::remove);
        boolean prepared;
        try {
            prepared = manager.prepareContraptionPlacement(serverLevel, targets, targetOrigins, finalPoses);
        } finally {
            displacedOwners.forEach(frameIndex::putIfAbsent);
        }
        if (prepared) {
            // Keep persistent ownership untouched until Create has written every destination block,
            // but expose the already-validated target mapping to synchronous vanilla support checks.
            CreateAssemblyPlacementContext.begin(serverLevel, targets, targetOrigins, finalPoses);
        }
        return prepared;
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
            return;
        }
        // Placement made the assembly eligible for static cross-Frame links again. Rebuild on the
        // post-tick boundary, after Create and every Frame BlockEntity have finished their writes.
        CreateMiniKineticLifecycle.scheduleAfterContraptionPlacement(serverLevel, ids);
    }

    private static Quaterniond snappedRotation(StructureTransform transform) {
        if (transform.rotationAxis == null || transform.angle == 0) return new Quaterniond();
        Direction.Axis axis = transform.rotationAxis;
        return new Quaterniond().rotateAxis(Math.toRadians(transform.angle),
                axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
    }
}
