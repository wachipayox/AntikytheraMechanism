package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * <p>Sable invokes {@code beforeMove}, writes the destination block and BlockEntity, then invokes
 * {@code afterMove}. The callback pair therefore also defines a narrow physical-relocation context
 * that prevents the copied destination Frame from registering itself as a brand-new Assembly before
 * the original Assembly's frame index is committed.</p>
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

        synchronized (RELOCATIONS) {
            Map<UUID, Relocation> byAssembly = RELOCATIONS.computeIfAbsent(
                    originLevel, ignored -> new HashMap<>());
            Relocation relocation = byAssembly.get(assembly.id());
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
                boolean journaled = manager.prepareContraptionMoves(
                        originLevel,
                        Map.of(assembly.id(), sourceFrames),
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
            }
            relocation.record(oldPosition, newPosition);
        }

        // Set this only after the persisted journal exists. LevelChunk#setBlockState/onPlace for the
        // destination run synchronously before Sable invokes afterMove on this same server thread.
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

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(originLevel);
        BlockPos delta = relocation.uniformTranslation();
        if (delta == null) {
            AntikytheraMechanism.LOGGER.error(
                    "Sable moved assembly {} with a non-translating Frame mapping; retaining its relocation journal for recovery",
                    relocation.assemblyId());
            forgetRuntimeMapping(originLevel, relocation.assemblyId());
            return;
        }

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

        boolean placementPrepared = manager.prepareContraptionPlacement(
                originLevel,
                Map.of(relocation.assemblyId(), targets),
                Map.of(relocation.assemblyId(), targetOrigin),
                Map.of(relocation.assemblyId(), finalLocalPose));
        if (!placementPrepared) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not prepare Sable destination for assembly {}; retaining its relocation journal for recovery",
                    relocation.assemblyId());
            forgetRuntimeMapping(originLevel, relocation.assemblyId());
            return;
        }

        boolean finalized = manager.finalizeContraptionPlacement(
                originLevel, java.util.List.of(relocation.assemblyId()));
        if (!finalized) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not finalize Sable relocation for assembly {}; persisted journal remains authoritative",
                    relocation.assemblyId());
            forgetRuntimeMapping(originLevel, relocation.assemblyId());
            return;
        }

        forgetRuntimeMapping(originLevel, relocation.assemblyId());
        AntikytheraMechanism.LOGGER.debug(
                "Adopted Sable relocation for assembly {} by {} into host {}",
                relocation.assemblyId(),
                delta,
                targetHost.kind());
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
    }
}
