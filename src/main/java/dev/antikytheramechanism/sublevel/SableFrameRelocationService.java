package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bridges Sable's block-by-block assembly callbacks into Antikythera's existing crash-safe complete
 * assembly relocation journal.
 *
 * <p>The first Frame callback journals both the complete source Frame set and its inferred complete
 * destination set before Sable writes the first destination block. Consequently the ordinary Frame
 * onPlace path already sees the destination as a physical relocation and cannot register a duplicate
 * logical Assembly while Sable is copying it.</p>
 */
public final class SableFrameRelocationService {
    private static final Map<ServerLevel, Map<UUID, Relocation>> RELOCATIONS = new WeakHashMap<>();
    private static final ThreadLocal<ActiveDestination> ACTIVE_DESTINATION = new ThreadLocal<>();

    private SableFrameRelocationService() {
    }

    public static boolean isDestinationTransition(ServerLevel level, BlockPos position) {
        ActiveDestination active = ACTIVE_DESTINATION.get();
        return active != null && active.level() == level && active.position().equals(position);
    }

    public static void beforeMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockPos oldPosition,
            BlockPos newPosition) {
        if (originLevel != resultingLevel) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(originLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(oldPosition).orElse(null);
        if (assembly == null) {
            return;
        }

        Relocation relocation;
        boolean created = false;
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.computeIfAbsent(
                    originLevel, ignored -> new HashMap<>());
            relocation = byAssembly.get(assembly.id());
            if (relocation == null) {
                if (manager.pendingPistonMove(assembly.id()).isPresent()
                        || manager.pendingContraptionMove(assembly.id()).isPresent()
                        || manager.isContentRecoveryLocked(assembly.id())) {
                    AntikytheraMechanism.LOGGER.error(
                            "Refusing concurrent Sable relocation for locked assembly {}",
                            assembly.id());
                    return;
                }

                Set<BlockPos> sourceFrames = Set.copyOf(assembly.frames());
                Map<BlockPos, BlockState> carriedBoundary = carriedBoundarySnapshot(originLevel, sourceFrames);
                boolean journaled = manager.prepareContraptionMoves(
                        originLevel,
                        Map.of(assembly.id(), sourceFrames),
                        Map.of(assembly.id(), carriedBoundary),
                        BlockPos.ZERO,
                        true);
                if (!journaled) {
                    AntikytheraMechanism.LOGGER.error(
                            "Could not journal Sable relocation for assembly {} before moving frame {}",
                            assembly.id(),
                            oldPosition);
                    return;
                }

                relocation = new Relocation(
                        assembly.id(),
                        sourceFrames,
                        assembly.origin(),
                        assembly.poseTarget());
                byAssembly.put(assembly.id(), relocation);
                created = true;
            }
            relocation.record(oldPosition, newPosition);
        }

        if (created) {
            BlockPos delta = newPosition.subtract(oldPosition).immutable();
            Set<BlockPos> targets = relocation.sourceFrames().stream()
                    .map(source -> source.offset(delta).immutable())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            BlockPos targetOrigin = relocation.sourceOrigin().offset(delta).immutable();

            MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(originLevel, targetOrigin);
            boolean oneUsableHost = targetHost.allowed()
                    && targets.stream().allMatch(target ->
                            MechanismAssemblyHost.sameResolvedHost(originLevel, targetOrigin, target));
            if (!oneUsableHost) {
                AntikytheraMechanism.LOGGER.error(
                        "Sable attempted to move assembly {} into unsupported or mixed host space {}; retaining its relocation journal for recovery",
                        relocation.assemblyId(),
                        targetHost.kind());
                forgetRuntimeMapping(originLevel, relocation.assemblyId());
                return;
            }

            AssemblyPose finalLocalPose = relocation.startPose().translated(
                    new Vector3d(delta.getX(), delta.getY(), delta.getZ()));
            boolean destinationJournaled = manager.prepareContraptionPlacement(
                    originLevel,
                    Map.of(relocation.assemblyId(), targets),
                    Map.of(relocation.assemblyId(), targetOrigin),
                    Map.of(relocation.assemblyId(), finalLocalPose));
            if (!destinationJournaled) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not journal complete Sable destination for assembly {} before its first Frame copy; retaining source relocation journal for recovery",
                        relocation.assemblyId());
                forgetRuntimeMapping(originLevel, relocation.assemblyId());
                return;
            }
            relocation.prepare(delta, targetOrigin);

            // Sable weighs a destination block immediately after beforeMove, then weighs the source
            // again later when it clears the old structure. Freeze the authoritative pre-relocation
            // Frame+payload mass now, while the logical Frame -> mini-cell mapping is still intact,
            // and reuse that exact value at both endpoints. This avoids reading already-relocated
            // mini coordinates from inside Sable's synchronous mass callback and keeps host mass
            // accounting symmetric for ROOT -> FOREIGN and FOREIGN -> ROOT moves.
            for (BlockPos sourceFrame : relocation.sourceFrames()) {
                SableAssemblyMoveContext.freezeFrameMass(
                        originLevel,
                        sourceFrame,
                        ManagedFrameMassPolicy.snapshotEffectiveFrameMass(originLevel, assembly, sourceFrame));
            }
        }

        // The persisted target journal above is the main relocation guard. Keep this narrow marker as
        // an additional low-level write hint for the exact synchronous destination setBlock call.
        ACTIVE_DESTINATION.set(new ActiveDestination(originLevel, newPosition.immutable()));
    }

    public static void afterMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockPos oldPosition,
            BlockPos newPosition) {
        clearActiveDestination(originLevel, newPosition);
        if (originLevel != resultingLevel) {
            return;
        }

        Relocation relocation;
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(originLevel);
            if (byAssembly == null) {
                return;
            }
            relocation = byAssembly.values().stream()
                    .filter(candidate -> candidate.sourceFrames().contains(oldPosition))
                    .findFirst()
                    .orElse(null);
        }
        if (relocation == null) {
            return;
        }

        relocation.record(oldPosition, newPosition);
        if (!relocation.complete()) {
            return;
        }

        BlockPos actualDelta = relocation.uniformTranslation();
        if (actualDelta == null
                || relocation.preparedDelta() == null
                || !actualDelta.equals(relocation.preparedDelta())) {
            AntikytheraMechanism.LOGGER.error(
                    "Sable moved assembly {} with a mapping different from its pre-journaled translation; retaining relocation journal for recovery",
                    relocation.assemblyId());
            forgetRuntimeMapping(originLevel, relocation.assemblyId());
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(originLevel);
        boolean finalized = manager.finalizeContraptionPlacement(
                originLevel, java.util.List.of(relocation.assemblyId()));
        if (!finalized) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not finalize Sable relocation for assembly {}; persisted journal remains authoritative",
                    relocation.assemblyId());
            forgetRuntimeMapping(originLevel, relocation.assemblyId());
            return;
        }

        MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(
                originLevel, relocation.targetOrigin());
        forgetRuntimeMapping(originLevel, relocation.assemblyId());
        AntikytheraMechanism.LOGGER.debug(
                "Adopted Sable relocation for assembly {} by {} into host {}",
                relocation.assemblyId(),
                actualDelta,
                targetHost.kind());
    }

    /**
     * Captures only parent blocks that are both face-adjacent to a Frame and part of this exact Sable
     * move. Stationary world neighbours are deliberately excluded: during relocation the managed
     * child may rely on carried structure, never on a stale block that Sable left behind.
     */
    private static Map<BlockPos, BlockState> carriedBoundarySnapshot(
            ServerLevel level,
            Set<BlockPos> sourceFrames) {
        Set<BlockPos> movedBlocks = SableAssemblyMoveContext.sourceBlocks(level);
        if (movedBlocks.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (BlockPos frame : sourceFrames) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = frame.relative(direction);
                if (!movedBlocks.contains(neighbor)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbor);
                if (state.isAir() || state.is(ModRegistries.MECHANISM_FRAME.get())) {
                    continue;
                }
                result.put(neighbor.immutable(), state);
            }
        }
        return Map.copyOf(result);
    }

    private static void clearActiveDestination(ServerLevel level, BlockPos position) {
        ActiveDestination active = ACTIVE_DESTINATION.get();
        if (active != null && active.level() == level && active.position().equals(position)) {
            ACTIVE_DESTINATION.remove();
        }
    }

    private static void forgetRuntimeMapping(ServerLevel level, UUID assemblyId) {
        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.get(level);
            if (byAssembly == null) {
                return;
            }
            byAssembly.remove(assemblyId);
            if (byAssembly.isEmpty()) {
                RELOCATIONS.remove(level);
            }
        }
    }

    private record ActiveDestination(ServerLevel level, BlockPos position) {
    }

    private static final class Relocation {
        private final UUID assemblyId;
        private final Set<BlockPos> sourceFrames;
        private final BlockPos sourceOrigin;
        private final AssemblyPose startPose;
        private final Map<BlockPos, BlockPos> destinations = new HashMap<>();
        private boolean invalidMapping;
        private BlockPos preparedDelta;
        private BlockPos targetOrigin;

        private Relocation(
                UUID assemblyId,
                Set<BlockPos> sourceFrames,
                BlockPos sourceOrigin,
                AssemblyPose startPose) {
            this.assemblyId = assemblyId;
            this.sourceFrames = sourceFrames;
            this.sourceOrigin = sourceOrigin.immutable();
            this.startPose = startPose;
        }

        void record(BlockPos source, BlockPos destination) {
            if (!sourceFrames.contains(source)) {
                return;
            }
            BlockPos previous = destinations.put(source.immutable(), destination.immutable());
            if (previous != null && !previous.equals(destination)) {
                invalidMapping = true;
            }
        }

        void prepare(BlockPos delta, BlockPos targetOrigin) {
            this.preparedDelta = delta.immutable();
            this.targetOrigin = targetOrigin.immutable();
        }

        boolean complete() {
            return !invalidMapping && destinations.keySet().containsAll(sourceFrames);
        }

        BlockPos uniformTranslation() {
            if (!complete()) {
                return null;
            }
            BlockPos expected = null;
            for (BlockPos source : sourceFrames) {
                BlockPos destination = destinations.get(source);
                BlockPos delta = destination.subtract(source);
                if (expected == null) {
                    expected = delta.immutable();
                } else if (!expected.equals(delta)) {
                    return null;
                }
            }
            return expected;
        }

        UUID assemblyId() {
            return assemblyId;
        }

        Set<BlockPos> sourceFrames() {
            return sourceFrames;
        }

        BlockPos sourceOrigin() {
            return sourceOrigin;
        }

        AssemblyPose startPose() {
            return startPose;
        }

        BlockPos preparedDelta() {
            return preparedDelta;
        }

        BlockPos targetOrigin() {
            return targetOrigin;
        }
    }
}
