package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * <p>Mini connectivity deliberately follows the same spatial neighbourhood used by Create 6.0.10
 * while traversing glued/sticky contraption blocks: only the six face neighbours are candidates.
 * We do not copy Create's glue/stickiness predicates because ordinary touching mini blocks are meant
 * to form one visually continuous payload even when neither BlockState is sticky.
 */
public final class HiddenFrameGeometryPolicy {
    private static final int SAFETY_SWEEP_INTERVAL = 20;
    private static final double FACE_EPSILON = 1.0E-7;

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
     * writes in a foreign host inspect only the changed host cell and its six face neighbours.
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
        manager.getAssemblyAt(position).ifPresent(assembly -> candidates.add(assembly.id()));
        for (Direction direction : Direction.values()) {
            manager.getAssemblyAt(position.relative(direction))
                    .ifPresent(assembly -> candidates.add(assembly.id()));
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
                // A write can arrive in the middle of Create/Sable/piston bookkeeping. Preserve the
                // request and evaluate only after the transaction releases its structural journal.
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
            // No persistent child means there is no payload. A referenced-but-unavailable child is
            // different: fail closed by waiting rather than destroying a user's HIDDEN choice.
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

        if (!isSingleFaceConnectedComponent(occupied)) {
            return Verdict.DISCONNECTED_MINI_CONTENT;
        }

        AnchorResult anchor = hasHostAnchor(level, assembly, child, foreignHost, occupied);
        return switch (anchor) {
            case FOUND -> Verdict.VALID;
            case MISSING -> Verdict.NO_HOST_ANCHOR;
            case UNKNOWN -> Verdict.DEFER;
        };
    }

    private static boolean isSingleFaceConnectedComponent(Set<BlockPos> occupied) {
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
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (occupied.contains(neighbor) && visited.add(neighbor)) {
                    frontier.addLast(neighbor);
                }
            }
        }
        return visited.size() == occupied.size();
    }

    private static AnchorResult hasHostAnchor(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel child,
            ServerSubLevel foreignHost,
            Set<BlockPos> occupied) {
        boolean unknown = false;
        UUID foreignHostId = foreignHost.getUniqueId();

        for (BlockPos frame : assembly.frames()) {
            for (Direction outward : Direction.values()) {
                BlockPos hostPosition = frame.relative(outward);
                if (assembly.containsFrame(hostPosition)) {
                    continue;
                }
                MechanismAssemblyHost.Resolution neighborHost = MechanismAssemblyHost.resolve(level, hostPosition);
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

                Direction hostFaceTowardFrame = outward.getOpposite();
                for (int a = 0; a < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; a++) {
                    for (int b = 0; b < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; b++) {
                        int x;
                        int y;
                        int z;
                        switch (outward.getAxis()) {
                            case X -> {
                                x = outward == Direction.WEST ? 0 : 1;
                                y = a;
                                z = b;
                            }
                            case Y -> {
                                x = a;
                                y = outward == Direction.DOWN ? 0 : 1;
                                z = b;
                            }
                            case Z -> {
                                x = a;
                                y = b;
                                z = outward == Direction.NORTH ? 0 : 1;
                            }
                            default -> throw new IllegalStateException("Unexpected axis " + outward.getAxis());
                        }

                        BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(
                                assembly, frame, x, y, z);
                        if (!occupied.contains(mini)) {
                            continue;
                        }
                        // The mini cell itself intentionally follows Create's block-position
                        // connectivity semantics. The host side is shape-aware so a slab/fence that
                        // does not physically reach the shared face cannot hide a visible air gap.
                        if (shapeTouchesFaceQuadrant(hostShape, hostFaceTowardFrame, a, b)) {
                            return AnchorResult.FOUND;
                        }
                    }
                }
            }
        }
        return unknown ? AnchorResult.UNKNOWN : AnchorResult.MISSING;
    }

    private static boolean shapeTouchesFaceQuadrant(
            VoxelShape shape,
            Direction face,
            int a,
            int b) {
        double u0 = a * MiniCoordinateMapper.SUBLEVEL_SCALE;
        double u1 = u0 + MiniCoordinateMapper.SUBLEVEL_SCALE;
        double v0 = b * MiniCoordinateMapper.SUBLEVEL_SCALE;
        double v1 = v0 + MiniCoordinateMapper.SUBLEVEL_SCALE;

        for (AABB box : shape.toAabbs()) {
            if (!touchesFace(box, face)) {
                continue;
            }

            double minU;
            double maxU;
            double minV;
            double maxV;
            switch (face.getAxis()) {
                case X -> {
                    minU = box.minY;
                    maxU = box.maxY;
                    minV = box.minZ;
                    maxV = box.maxZ;
                }
                case Y -> {
                    minU = box.minX;
                    maxU = box.maxX;
                    minV = box.minZ;
                    maxV = box.maxZ;
                }
                case Z -> {
                    minU = box.minX;
                    maxU = box.maxX;
                    minV = box.minY;
                    maxV = box.maxY;
                }
                default -> throw new IllegalStateException("Unexpected axis " + face.getAxis());
            }
            if (overlaps(minU, maxU, u0, u1) && overlaps(minV, maxV, v0, v1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesFace(AABB box, Direction face) {
        return switch (face) {
            case WEST -> box.minX <= FACE_EPSILON;
            case EAST -> box.maxX >= 1.0 - FACE_EPSILON;
            case DOWN -> box.minY <= FACE_EPSILON;
            case UP -> box.maxY >= 1.0 - FACE_EPSILON;
            case NORTH -> box.minZ <= FACE_EPSILON;
            case SOUTH -> box.maxZ >= 1.0 - FACE_EPSILON;
        };
    }

    private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB) > FACE_EPSILON;
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
        DISCONNECTED_MINI_CONTENT("mini payload is split into multiple face-connected components"),
        NO_HOST_ANCHOR("no occupied mini cell physically reaches the foreign host"),
        DEFER("required topology is temporarily unavailable");

        private final String description;

        Verdict(String description) {
            this.description = description;
        }
    }
}
