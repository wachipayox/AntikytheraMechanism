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
 * <p>Visual continuity uses all 26 touching neighbours. Mini blocks may connect by face, edge or
 * corner, including across different Mechanism assemblies, as long as every participant is physically
 * hosted by the same foreign Sable SubLevel. Connectivity is evaluated in one host-local half-block
 * lattice instead of each assembly's private logical mini coordinates. Empty member Frames are valid:
 * only the geometry that actually exists must form one supported touching component.
 */
public final class HiddenFrameGeometryPolicy {
    private static final int SAFETY_SWEEP_INTERVAL = 20;
    private static final double CONTACT_EPSILON = 1.0E-7;

    private static final Map<ServerLevel, Set<UUID>> PENDING = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LAST_SWEEP = new WeakHashMap<>();

    private HiddenFrameGeometryPolicy() {
    }

    public static void request(ServerLevel level, @Nullable UUID assemblyId) {
        if (assemblyId == null) {
            return;
        }
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, ignored -> new HashSet<>()).add(assemblyId);
        }
    }

    /**
     * Managed-child changes can affect another hidden assembly through cross-assembly mini contact,
     * so peers in the same foreign host are dirtied together. Foreign-host writes only fan out when
     * they are within one macro block of any Frame in that host.
     */
    public static void requestForSuccessfulWrite(
            ServerLevel level,
            BlockPos position,
            @Nullable SubLevel containing) {
        if (!(containing instanceof ServerSubLevel serverSubLevel) || serverSubLevel.isRemoved()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        UUID managedOwner = MechanismSubLevelService.getOwnerAssemblyId(serverSubLevel);
        if (managedOwner != null) {
            request(level, managedOwner);
            MechanismAssembly owner = manager.getAssembly(managedOwner).orElse(null);
            if (owner != null) {
                MechanismAssemblyHost.Resolution ownerHost = MechanismAssemblyHost.resolve(level, owner.origin());
                if (ownerHost.kind() == MechanismAssemblyHost.Kind.FOREIGN && ownerHost.subLevel() != null) {
                    requestHiddenAssembliesInHost(level, manager, ownerHost.subLevel().getUniqueId());
                }
            }
            return;
        }

        Set<UUID> nearbyAssemblies = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    manager.getAssemblyAt(position.offset(dx, dy, dz))
                            .ifPresent(assembly -> nearbyAssemblies.add(assembly.id()));
                }
            }
        }
        if (nearbyAssemblies.isEmpty()) {
            return;
        }

        UUID hostId = serverSubLevel.getUniqueId();
        boolean touchesFrameInThisHost = false;
        for (UUID assemblyId : nearbyAssemblies) {
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly != null && isHostedBy(level, assembly, hostId)) {
                touchesFrameInThisHost = true;
                break;
            }
        }
        if (touchesFrameInThisHost) {
            requestHiddenAssembliesInHost(level, manager, hostId);
        }
    }

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

            Verdict verdict = evaluate(level, manager, assembly, host.subLevel());
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

    private static Verdict evaluate(
            ServerLevel level,
            MechanismAssemblyManager manager,
            MechanismAssembly assembly,
            ServerSubLevel foreignHost) {
        for (BlockPos frame : assembly.frames()) {
            if (!level.hasChunkAt(frame)
                    || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())) {
                return Verdict.DEFER;
            }
        }

        ServerSubLevel ownChild = MechanismSubLevelService.findExisting(level, assembly);
        if (ownChild == null) {
            // No payload means there is no mini component that can explain/support a hidden cage.
            // A referenced-but-temporarily-unavailable child is different: wait for it to load.
            return assembly.subLevelId() == null ? Verdict.NO_HOST_ANCHOR : Verdict.DEFER;
        }
        if (ownChild.isRemoved()) {
            return Verdict.DEFER;
        }

        Set<BlockPos> ownOccupied = new HashSet<>();
        for (BlockPos frame : assembly.frames()) {
            collectPhysicalOccupied(assembly, ownChild, frame, ownOccupied);
        }
        if (ownOccupied.isEmpty()) {
            return Verdict.NO_HOST_ANCHOR;
        }

        HostGeometry geometry = collectHostGeometry(level, manager, foreignHost, assembly, ownOccupied);
        Set<BlockPos> component = touchComponent(ownOccupied.iterator().next(), geometry.occupied());
        if (!component.containsAll(ownOccupied)) {
            return geometry.incomplete() ? Verdict.DEFER : Verdict.DISCONNECTED_MINI_CONTENT;
        }

        AnchorResult anchor = hasHostAnchor(level, foreignHost, component);
        if (anchor == AnchorResult.FOUND) {
            return Verdict.VALID;
        }
        if (anchor == AnchorResult.UNKNOWN || geometry.incomplete()) {
            return Verdict.DEFER;
        }
        return Verdict.NO_HOST_ANCHOR;
    }

    /**
     * Builds a shared host-local mini lattice. The coordinate is simply macroFrame*2 + physicalCell,
     * so private assembly origins/orientations cannot hide a real diagonal contact from another Frame.
     */
    private static HostGeometry collectHostGeometry(
            ServerLevel level,
            MechanismAssemblyManager manager,
            ServerSubLevel foreignHost,
            MechanismAssembly ownAssembly,
            Set<BlockPos> ownOccupied) {
        Set<BlockPos> occupied = new HashSet<>(ownOccupied);
        boolean incomplete = false;
        UUID hostId = foreignHost.getUniqueId();

        for (MechanismAssembly peer : manager.assemblies()) {
            if (peer.id().equals(ownAssembly.id()) || !isHostedBy(level, peer, hostId)) {
                continue;
            }
            if (isMutationLocked(manager, peer.id())) {
                incomplete = true;
                continue;
            }

            ServerSubLevel peerChild = MechanismSubLevelService.findExisting(level, peer);
            if (peerChild == null) {
                if (peer.subLevelId() != null) {
                    incomplete = true;
                }
                continue;
            }
            if (peerChild.isRemoved()) {
                incomplete = true;
                continue;
            }

            for (BlockPos frame : peer.frames()) {
                collectPhysicalOccupied(peer, peerChild, frame, occupied);
            }
        }
        return new HostGeometry(Set.copyOf(occupied), incomplete);
    }

    private static void collectPhysicalOccupied(
            MechanismAssembly assembly,
            ServerSubLevel child,
            BlockPos frame,
            Set<BlockPos> destination) {
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, x, y, z);
                    if (child.getPlot().getEmbeddedLevelAccessor().getBlockState(mini).isAir()) {
                        continue;
                    }
                    destination.add(physicalMiniPosition(frame, x, y, z));
                }
            }
        }
    }

    private static BlockPos physicalMiniPosition(BlockPos frame, int x, int y, int z) {
        return new BlockPos(
                frame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + x,
                frame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + y,
                frame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + z);
    }

    private static Set<BlockPos> touchComponent(BlockPos first, Set<BlockPos> occupied) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
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
        return visited;
    }

    /**
     * Any mini in the connected component may provide the host anchor, even if it belongs to another
     * Mechanism assembly. This matches the visible structural question: the complete touching payload
     * is supported somewhere by the same host, rather than every Frame group requiring its own foot.
     */
    private static AnchorResult hasHostAnchor(
            ServerLevel level,
            ServerSubLevel foreignHost,
            Set<BlockPos> component) {
        boolean unknown = false;
        UUID hostId = foreignHost.getUniqueId();
        double scale = MiniCoordinateMapper.SUBLEVEL_SCALE;

        for (BlockPos physicalMini : component) {
            BlockPos frame = new BlockPos(
                    Math.floorDiv(physicalMini.getX(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                    Math.floorDiv(physicalMini.getY(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                    Math.floorDiv(physicalMini.getZ(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS));
            int cellX = Math.floorMod(physicalMini.getX(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            int cellY = Math.floorMod(physicalMini.getY(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            int cellZ = Math.floorMod(physicalMini.getZ(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            AABB miniBox = new AABB(
                    cellX * scale,
                    cellY * scale,
                    cellZ * scale,
                    (cellX + 1) * scale,
                    (cellY + 1) * scale,
                    (cellZ + 1) * scale);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos hostPosition = frame.offset(dx, dy, dz);
                        MechanismAssemblyHost.Resolution neighborHost =
                                MechanismAssemblyHost.resolve(level, hostPosition);
                        if (neighborHost.kind() != MechanismAssemblyHost.Kind.FOREIGN
                                || neighborHost.subLevel() == null
                                || !hostId.equals(neighborHost.subLevel().getUniqueId())) {
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
                        if (shapeTouchesMiniCell(hostShape, dx, dy, dz, miniBox)) {
                            return AnchorResult.FOUND;
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

    private static void requestHiddenAssembliesInHost(
            ServerLevel level,
            MechanismAssemblyManager manager,
            UUID hostId) {
        for (MechanismAssembly assembly : manager.assemblies()) {
            if (assembly.shellMode() == FrameShellMode.HIDDEN && isHostedBy(level, assembly, hostId)) {
                request(level, assembly.id());
            }
        }
    }

    private static boolean isHostedBy(ServerLevel level, MechanismAssembly assembly, UUID hostId) {
        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
        return host.kind() == MechanismAssemblyHost.Kind.FOREIGN
                && host.subLevel() != null
                && hostId.equals(host.subLevel().getUniqueId());
    }

    private static boolean isMutationLocked(MechanismAssemblyManager manager, UUID assemblyId) {
        return manager.isContentRecoveryLocked(assemblyId)
                || manager.pendingPistonMove(assemblyId).isPresent()
                || manager.pendingContraptionMove(assemblyId).isPresent()
                || manager.pendingFrameEvacuation(assemblyId).isPresent();
    }

    private static Set<UUID> drain(ServerLevel level) {
        synchronized (PENDING) {
            Set<UUID> pending = PENDING.remove(level);
            return pending == null ? new HashSet<>() : new HashSet<>(pending);
        }
    }

    private record HostGeometry(Set<BlockPos> occupied, boolean incomplete) {
    }

    private enum AnchorResult {
        FOUND,
        MISSING,
        UNKNOWN
    }

    private enum Verdict {
        VALID("geometry remains structurally self-explanatory"),
        DISCONNECTED_MINI_CONTENT("mini payload is split into multiple touching components"),
        NO_HOST_ANCHOR("the touching mini component does not physically reach the foreign host"),
        DEFER("required topology is temporarily unavailable");

        private final String description;

        Verdict(String description) {
            this.description = description;
        }
    }
}
