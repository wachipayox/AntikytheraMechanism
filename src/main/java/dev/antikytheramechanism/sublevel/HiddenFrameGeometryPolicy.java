package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Rejects structurally misleading HIDDEN Frame presentation inside a foreign Sable host.
 *
 * <p>HIDDEN is a persisted user choice, not a derived renderer state. When hosted mini geometry no
 * longer makes sense without the cage, this policy permanently changes the assembly back to NORMAL.
 * Restoring valid geometry later never hides it again; the player must explicitly request HIDDEN.
 *
 * <p>Create's contraption traversal itself starts from six face-neighbours, but the presentation rule
 * here intentionally uses all 26 touching neighbours. For visual structural continuity, mini blocks
 * touching by face, edge or corner are connected, and the same contact rule applies mini -> host.
 */
public final class HiddenFrameGeometryPolicy {
    private static final int SAFETY_SWEEP_INTERVAL = 20;
    private static final double CONTACT_EPSILON = 1.0E-7;

    private static final Map<ServerLevel, Set<UUID>> PENDING = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LAST_SWEEP = new WeakHashMap<>();

    private HiddenFrameGeometryPolicy() {
    }

    /** Queues one assembly for a post-tick topology check. Duplicate writes collapse to one check. */
    public static void request(ServerLevel level, @Nullable UUID assemblyId) {
        if (assemblyId == null) {
            return;
        }
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, ignored -> new HashSet<>()).add(assemblyId);
        }
    }

    /**
     * Called after a concrete LevelChunk write. Managed-child writes identify their owner directly;
     * writes in a foreign host inspect the changed host cell plus all 26 touching Frame positions.
     * Root-world writes are irrelevant because this policy intentionally applies only to hosted Frames.
     */
    public static void requestForSuccessfulWrite(
            ServerLevel level,
            BlockPos position,
            @Nullable SubLevel containing) {
        if (!(containing instanceof ServerSubLevel serverSubLevel) || serverSubLevel.isRemoved()) {
            return;
        }

        UUID managedOwner = MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel);
        if (managedOwner != null) {
            request(level, managedOwner);
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> candidates = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = position.offset(dx, dy, dz);
                    manager.getAssemblyAt(candidate).ifPresent(assembly -> candidates.add(assembly.id()));
                }
            }
        }

        UUID hostId = serverSubLevel.getUniqueId();
        for (UUID assemblyId : candidates) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null) {
                continue;
            }
            MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
            if (host.kind() == MechanismAssemblyHost.Kind.FOREIGN
                    && host.subLevel() != null
                    && hostId.equals(host.subLevel().getUniqueId())) {
                request(level, assemblyId);
            }
        }
    }

    /** Runs after normal manager reconciliation, never re-entrantly inside a block write. */
    public static void tick(ServerLevel level) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> candidates = drain(level);

        long gameTime = level.getGameTime();
        boolean safetySweep;
        synchronized (PENDING) {
            Long lastSweep = LAST_SWEEP.get(level);
            safetySweep = lastSweep == null || gameTime - lastSweep >= SAFETY_SWEEP_INTERVAL;
            if (safetySweep) {
                LAST_SWEEP.put(level, gameTime);
            }
        }
        if (safetySweep) {
            for (MechanismAssembly assembly : manager.assemblies()) {
                if (assembly.shellMode() == FrameShellMode.HIDDEN) {
                    candidates.add(assembly.id());
                }
            }
        }

        for (UUID assemblyId : candidates) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null || assembly.shellMode() != FrameShellMode.HIDDEN) {
                continue;
            }
            if (isMutationLocked(manager, assemblyId)) {
                request(level, assemblyId);
                continue;
            }

            MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
            if (host.kind() != MechanismAssemblyHost.Kind.FOREIGN || host.subLevel() == null) {
                continue;
            }

            Verdict verdict = evaluate(level, assembly, host.subLevel());
            if (verdict == Verdict.DEFER || verdict == Verdict.VALID) {
                if (verdict == Verdict.DEFER) {
                    request(level, assemblyId);
                }
                continue;
            }

            if (!manager.setFrameShellMode(level, assembly.origin(), FrameShellMode.NORMAL)) {
                request(level, assemblyId);
                continue;
            }
            AntikytheraMechanism.LOGGER.debug(
                    "Restored hosted assembly {} from HIDDEN to NORMAL because {}",
                    assemblyId,
                    verdict.description);
        }
    }

    private static boolean isMutationLocked(MechanismAssemblyManager manager, UUID assemblyId) {
        return manager.isContentRecoveryLocked(assemblyId)
                || manager.pendingPistonMove(assemblyId).isPresent()
                || manager.pendingContraptionMove(assemblyId).isPresent()
                || manager.pendingFrameEvacuation(assemblyId).isPresent();
    }

    private static Verdict evaluate(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel foreignHost) {
        for (BlockPos frame : assembly.frames()) {
            if (!level.hasChunkAt(frame)
                    || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())) {
                return Verdict.DEFER;
            }
        }

        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null) {
            return assembly.subLevelId() == null ? Verdict.EMPTY_FRAME : Verdict.DEFER;
        }
        if (child.isRemoved()) {
            return Verdict.DEFER;
        }

        Set<BlockPos> occupied = new HashSet<>();
        for (BlockPos frame : assembly.frames()) {
            int occupiedInFrame = 0;
            for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                    for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                        if (!child.getPlot().getEmbeddedLevelAccessor().getBlockState(mini).isAir()) {
                            occupied.add(mini.immutable());
                            occupiedInFrame++;
                        }
                    }
                }
            }
            if (occupiedInFrame == 0) {
                return Verdict.EMPTY_FRAME;
            }
        }

        if (!isSingleTouchConnectedComponent(occupied)) {
            return Verdict.DISCONNECTED_MINI_CONTENT;
        }

        AnchorResult anchor = hasHostAnchor(level, assembly, foreignHost, occupied);
        return switch (anchor) {
            case FOUND -> Verdict.VALID;
            case MISSING -> Verdict.NO_HOST_ANCHOR;
            case UNKNOWN -> Verdict.DEFER;
        };
    }

    /** 26-neighbour connectivity: touching by face, edge or corner is structurally continuous. */
    private static boolean isSingleTouchConnectedComponent(Set<BlockPos> occupied) {
        if (occupied.isEmpty()) {
            return false;
        }
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        BlockPos first = occupied.iterator().next();
        visited.add(first);
        frontier.add(first);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (occupied.contains(neighbor) && visited.add(neighbor)) {
                            frontier.addLast(neighbor);
                        }
                    }
                }
            }
        }
        return visited.size() == occupied.size();
    }

    /**
     * Searches every macro block touching a Frame's 1x1x1 volume, including edge/corner diagonals.
     * An occupied half-block mini cell anchors when its own AABB touches the real collision geometry
     * of a non-Frame block belonging to the same foreign host. Exact zero-area edge/corner contact is
     * intentionally valid; any positive air gap is not.
     */
    private static AnchorResult hasHostAnchor(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel foreignHost,
            Set<BlockPos> occupied) {
        boolean unknown = false;
        UUID foreignHostId = foreignHost.getUniqueId();
        double scale = MiniCoordinateMapper.SUBLEVEL_SCALE;

        for (BlockPos frame : assembly.frames()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos hostPosition = frame.offset(dx, dy, dz);
                        if (assembly.containsFrame(hostPosition)) {
                            continue;
                        }

                        MechanismAssemblyHost.Resolution neighborHost =
                                MechanismAssemblyHost.resolve(level, hostPosition);
                        if (neighborHost.kind() != MechanismAssemblyHost.Kind.FOREIGN
                                || neighborHost.subLevel() == null
                                || !foreignHostId.equals(neighborHost.subLevel().getUniqueId())) {
                            continue;
                        }
                        if (!level.hasChunkAt(hostPosition)) {
                            unknown = true;
                            continue;
                        }

                        BlockState hostState = level.getChunkAt(hostPosition).getBlockState(hostPosition);
                        if (hostState.isAir() || hostState.is(ModRegistries.MECHANISM_FRAME.get())) {
                            continue;
                        }
                        VoxelShape hostShape = hostState.getCollisionShape(
                                level, hostPosition, CollisionContext.empty());
                        if (hostShape.isEmpty()) {
                            continue;
                        }

                        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                                    BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(
                                            assembly, frame, x, y, z);
                                    if (!occupied.contains(mini)) {
                                        continue;
                                    }
                                    AABB miniBox = new AABB(
                                            x * scale,
                                            y * scale,
                                            z * scale,
                                            (x + 1) * scale,
                                            (y + 1) * scale,
                                            (z + 1) * scale);
                                    if (shapeTouchesMiniCell(hostShape, dx, dy, dz, miniBox)) {
                                        return AnchorResult.FOUND;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return unknown ? AnchorResult.UNKNOWN : AnchorResult.MISSING;
    }

    private static boolean shapeTouchesMiniCell(
            VoxelShape hostShape,
            int hostOffsetX,
            int hostOffsetY,
            int hostOffsetZ,
            AABB miniBox) {
        for (AABB hostLocalBox : hostShape.toAabbs()) {
            AABB hostBox = hostLocalBox.move(hostOffsetX, hostOffsetY, hostOffsetZ);
            if (touchesOrOverlaps(miniBox.minX, miniBox.maxX, hostBox.minX, hostBox.maxX)
                    && touchesOrOverlaps(miniBox.minY, miniBox.maxY, hostBox.minY, hostBox.maxY)
                    && touchesOrOverlaps(miniBox.minZ, miniBox.maxZ, hostBox.minZ, hostBox.maxZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesOrOverlaps(double minA, double maxA, double minB, double maxB) {
        return Math.min(maxA, maxB) + CONTACT_EPSILON >= Math.max(minA, minB);
    }

    private static Set<UUID> drain(ServerLevel level) {
        synchronized (PENDING) {
            Set<UUID> pending = PENDING.remove(level);
            return pending == null ? new HashSet<>() : new HashSet<>(pending);
        }
    }

    private enum AnchorResult {
        FOUND,
        MISSING,
        UNKNOWN
    }

    private enum Verdict {
        VALID("geometry remains structurally self-explanatory"),
        EMPTY_FRAME("at least one Frame contains no mini blocks"),
        DISCONNECTED_MINI_CONTENT("mini payload is split into multiple touching components"),
        NO_HOST_ANCHOR("no occupied mini cell physically touches the foreign host"),
        DEFER("required topology is temporarily unavailable");

        private final String description;

        Verdict(String description) {
            this.description = description;
        }
    }
}
