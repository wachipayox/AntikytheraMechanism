package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.collider.SableCollisionContext;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Makes the real mini geometry of a FOREIGN-hosted MechanismAssembly part of the foreign host's
 * rigid body.
 *
 * <p>The managed child remains the authoritative Minecraft level for blocks, block entities,
 * ticking and rendering. It must not remain a second solver body, though: any contact response on
 * that pose-driven child is erased by {@link AssemblyPoseDriver}. Sable's Rapier backend already
 * models mounted kinematic contraptions as zero-density LevelColliders parented to an existing
 * rigid body. This bridge reuses that representation for mini geometry.</p>
 *
 * <p>Each physical Frame is represented by one synthetic host-local voxel. The eight logical
 * 0.5-scale mini cells inside that Frame are baked into that voxel as collision boxes after applying
 * {@link FrameOrientation}. The mounted collider therefore contributes geometry but no mass;
 * {@link HostedMiniMassBridge} remains the single authority for projected mini mass/inertia.</p>
 */
public final class HostedMiniCollisionBridge {
    private static final double HALF = MiniCoordinateMapper.SUBLEVEL_SCALE;
    private static final double DEFAULT_FRICTION = 1.0;
    private static final double DEFAULT_RESTITUTION = 0.0;

    private static final Map<ServerLevel, Map<UUID, Binding>> BINDINGS = new WeakHashMap<>();
    private static final Map<FrameColliderKey, RapierVoxelColliderData> COLLIDER_CACHE = new HashMap<>();

    private HostedMiniCollisionBridge() {
    }

    /**
     * Reconciles all FOREIGN-hosted managed children immediately before one Sable physics substep.
     *
     * <p>This method intentionally does nothing when Rapier is unavailable. In that case the
     * managed child's normal Sable representation is left untouched.</p>
     */
    public static void reconcile(
            ServerLevel level,
            PhysicsPipeline pipeline,
            MechanismAssemblyManager manager) {
        if (!Rapier3D.ENABLED) {
            return;
        }

        Map<UUID, Binding> bindings =
                BINDINGS.computeIfAbsent(level, ignored -> new HashMap<>());
        Set<UUID> seen = new HashSet<>();

        for (MechanismAssembly assembly : manager.assemblies()) {
            seen.add(assembly.id());

            ServerSubLevel child = MechanismSubLevelService.get(level, assembly);
            if (child == null
                    || child.isRemoved()
                    || manager.isContentRecoveryLocked(assembly.id())) {
                removeBinding(level, pipeline, bindings.remove(assembly.id()), true);
                continue;
            }

            HostedMiniPhysicalAttachment.Attachment attachment =
                    HostedMiniPhysicalAttachment.resolve(level, assembly, child);
            if (attachment == null) {
                removeBinding(level, pipeline, bindings.remove(assembly.id()), true);
                continue;
            }

            try {
                Binding binding = bindings.get(assembly.id());
                if (binding == null
                        || binding.childRuntimeId() != Rapier3D.getID(child)
                        || binding.hostRuntimeId() != Rapier3D.getID(attachment.physicalBody())) {
                    removeBinding(level, pipeline, binding, true);
                    binding = createBinding(level, attachment);
                    bindings.put(assembly.id(), binding);
                }

                updateMountedTransform(level, attachment, binding);

                long gameTime = level.getGameTime();
                if (binding.lastGeometryCheckGameTime() != gameTime) {
                    long signature = stateSignature(level, attachment);
                    binding.lastGeometryCheckGameTime(gameTime);
                    if (signature != binding.stateSignature()) {
                        rebuild(level, attachment, binding, signature);
                    }
                }

                // Only suppress the child's own solver collider after the mounted proxy exists.
                // Java-side plot/query/raycast geometry remains untouched.
                suppressChildSolverCollider(level, attachment);
            } catch (RuntimeException | LinkageError exception) {
                Binding failed = bindings.remove(assembly.id());
                removeBinding(level, pipeline, failed, true);
                AntikytheraMechanism.LOGGER.error(
                        "Failed to reconcile hosted mini collision proxy for assembly {}",
                        assembly.id(),
                        exception);
            }
        }

        List<UUID> stale = bindings.keySet().stream()
                .filter(id -> !seen.contains(id))
                .toList();
        for (UUID id : stale) {
            removeBinding(level, pipeline, bindings.remove(id), false);
        }

        if (bindings.isEmpty()) {
            BINDINGS.remove(level);
        }
    }

    static int activeProxyCount(ServerLevel level) {
        Map<UUID, Binding> bindings = BINDINGS.get(level);
        return bindings == null ? 0 : bindings.size();
    }

    private static Binding createBinding(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment) {
        int sceneId = Rapier3D.getID(level);
        int proxyId = Rapier3D.nextBodyID();
        int hostId = Rapier3D.getID(attachment.physicalBody());

        Rapier3D.createKinematicContraption(
                sceneId,
                hostId,
                proxyId,
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0});

        Binding binding = new Binding(
                proxyId,
                Rapier3D.getID(attachment.logicalBody()),
                hostId,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                attachment.logicalBody());
        rebuild(level, attachment, binding, stateSignature(level, attachment));
        return binding;
    }

    private static void rebuild(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment,
            Binding binding,
            long signature) {
        int sceneId = Rapier3D.getID(level);

        // There is no per-section removal operation for mounted LevelColliders. Recreate the
        // zero-density proxy with the same runtime ID so removed mini blocks cannot leave stale
        // sections behind.
        Rapier3D.removeKinematicContraption(sceneId, binding.proxyRuntimeId());
        Rapier3D.createKinematicContraption(
                sceneId,
                binding.hostRuntimeId(),
                binding.proxyRuntimeId(),
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0});

        List<BlockPos> frames = sortedFrames(attachment.assembly());
        if (frames.isEmpty()) {
            binding.stateSignature(signature);
            return;
        }

        BlockPos origin = attachment.assembly().origin();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Map<Long, int[]> sections = new HashMap<>();

        for (BlockPos frame : frames) {
            BlockPos physicalOffset = frame.subtract(origin);
            minX = Math.min(minX, physicalOffset.getX());
            minY = Math.min(minY, physicalOffset.getY());
            minZ = Math.min(minZ, physicalOffset.getZ());
            maxX = Math.max(maxX, physicalOffset.getX());
            maxY = Math.max(maxY, physicalOffset.getY());
            maxZ = Math.max(maxZ, physicalOffset.getZ());

            FrameGeometry geometry = frameGeometry(level, attachment, frame);
            if (geometry.boxes().isEmpty()) {
                continue;
            }

            RapierVoxelColliderData colliderData =
                    COLLIDER_CACHE.computeIfAbsent(
                            geometry.key(),
                            ignored -> createColliderData(geometry));

            SectionPos sectionPos = SectionPos.of(physicalOffset);
            int[] section = sections.computeIfAbsent(
                    sectionPos.asLong(),
                    ignored -> new int[LevelChunkSection.SECTION_SIZE]);
            int index = (physicalOffset.getX() & 15)
                    + ((physicalOffset.getZ() & 15) << 4)
                    + ((physicalOffset.getY() & 15) << 8);
            section[index] = pack(colliderData);
        }

        updateMountedTransform(level, attachment, binding);
        for (Map.Entry<Long, int[]> entry : sections.entrySet()) {
            SectionPos sectionPos = SectionPos.of(entry.getKey());
            Rapier3D.addKinematicContraptionChunkSection(
                    sceneId,
                    binding.proxyRuntimeId(),
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    entry.getValue());
        }
        Rapier3D.setLocalBounds(
                sceneId,
                binding.proxyRuntimeId(),
                minX, minY, minZ,
                maxX, maxY, maxZ);
        binding.stateSignature(signature);
    }

    private static FrameGeometry frameGeometry(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment,
            BlockPos frame) {
        List<BoxKey> boxes = new ArrayList<>();
        double weightedFriction = 0.0;
        double weightedRestitution = 0.0;
        double physicalShapeVolume = 0.0;
        double buoyancyVolume = 0.0;

        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                            attachment.assembly(), frame, x, y, z);
                    BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(
                            attachment.logicalBody(), miniLocal);
                    BlockState state = level.getBlockState(miniGlobal);
                    if (state.isAir()) {
                        continue;
                    }

                    VoxelShape shape;
                    if (state.getBlock() instanceof BlockSubLevelCollisionShape customShape) {
                        shape = customShape.getSubLevelCollisionShape(level, state);
                    } else {
                        shape = state.getCollisionShape(
                                level,
                                miniGlobal,
                                SableCollisionContext.get());
                    }
                    if (shape.isEmpty()) {
                        continue;
                    }

                    final double friction = PhysicsBlockPropertyHelper.getFriction(state);
                    final double restitution = PhysicsBlockPropertyHelper.getRestitution(state);
                    buoyancyVolume += PhysicsBlockPropertyHelper.getVolume(state)
                            * HALF * HALF * HALF;

                    final int cellX = x;
                    final int cellY = y;
                    final int cellZ = z;
                    final double[] cellVolume = {0.0};
                    shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                        double clippedMinX = Math.max(0.0, minX);
                        double clippedMinY = Math.max(0.0, minY);
                        double clippedMinZ = Math.max(0.0, minZ);
                        double clippedMaxX = Math.min(1.0, maxX);
                        double clippedMaxY = Math.min(1.0, maxY);
                        double clippedMaxZ = Math.min(1.0, maxZ);
                        if (clippedMinX >= clippedMaxX
                                || clippedMinY >= clippedMaxY
                                || clippedMinZ >= clippedMaxZ) {
                            return;
                        }

                        BoxKey transformed = transformMiniBox(
                                attachment.assembly().orientation(),
                                cellX, cellY, cellZ,
                                clippedMinX, clippedMinY, clippedMinZ,
                                clippedMaxX, clippedMaxY, clippedMaxZ);
                        boxes.add(transformed);
                        cellVolume[0] += transformed.volume();
                    });

                    weightedFriction += friction * cellVolume[0];
                    weightedRestitution += restitution * cellVolume[0];
                    physicalShapeVolume += cellVolume[0];
                }
            }
        }

        double friction = physicalShapeVolume > 0.0
                ? weightedFriction / physicalShapeVolume
                : DEFAULT_FRICTION;
        double restitution = physicalShapeVolume > 0.0
                ? weightedRestitution / physicalShapeVolume
                : DEFAULT_RESTITUTION;

        FrameColliderKey key = new FrameColliderKey(
                List.copyOf(boxes),
                Double.doubleToLongBits(friction),
                Double.doubleToLongBits(buoyancyVolume),
                Double.doubleToLongBits(restitution));
        return new FrameGeometry(List.copyOf(boxes), friction, buoyancyVolume, restitution, key);
    }

    static BoxKey transformMiniBox(
            FrameOrientation orientation,
            int cellX,
            int cellY,
            int cellZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double logicalMinX = (cellX + minX) * HALF;
        double logicalMinY = (cellY + minY) * HALF;
        double logicalMinZ = (cellZ + minZ) * HALF;
        double logicalMaxX = (cellX + maxX) * HALF;
        double logicalMaxY = (cellY + maxY) * HALF;
        double logicalMaxZ = (cellZ + maxZ) * HALF;

        double outMinX = Double.POSITIVE_INFINITY;
        double outMinY = Double.POSITIVE_INFINITY;
        double outMinZ = Double.POSITIVE_INFINITY;
        double outMaxX = Double.NEGATIVE_INFINITY;
        double outMaxY = Double.NEGATIVE_INFINITY;
        double outMaxZ = Double.NEGATIVE_INFINITY;
        Vector3d transformed = new Vector3d();

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    orientation.logicalLocalToPhysical(
                            xi == 0 ? logicalMinX : logicalMaxX,
                            yi == 0 ? logicalMinY : logicalMaxY,
                            zi == 0 ? logicalMinZ : logicalMaxZ,
                            transformed);
                    outMinX = Math.min(outMinX, transformed.x);
                    outMinY = Math.min(outMinY, transformed.y);
                    outMinZ = Math.min(outMinZ, transformed.z);
                    outMaxX = Math.max(outMaxX, transformed.x);
                    outMaxY = Math.max(outMaxY, transformed.y);
                    outMaxZ = Math.max(outMaxZ, transformed.z);
                }
            }
        }

        return new BoxKey(
                outMinX, outMinY, outMinZ,
                outMaxX, outMaxY, outMaxZ);
    }

    private static RapierVoxelColliderData createColliderData(FrameGeometry geometry) {
        // A mounted LevelCollider is explicitly zero-density in Sable's native backend. The volume
        // here is retained for Sable's collider metadata only; it does not replace HostedMiniMassBridge.
        RapierVoxelColliderData data = Rapier3D.createVoxelColliderEntry(
                geometry.friction(),
                geometry.buoyancyVolume(),
                geometry.restitution(),
                false,
                null);
        for (BoxKey box : geometry.boxes()) {
            data.addBox(
                    new Vector3d(box.minX(), box.minY(), box.minZ()),
                    new Vector3d(box.maxX(), box.maxY(), box.maxZ()));
        }
        return data;
    }

    private static void updateMountedTransform(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment,
            Binding binding) {
        Vector3dc hostCenter = attachment.physicalBody()
                .getMassTracker()
                .getCenterOfMass();
        if (hostCenter == null) {
            return;
        }

        BlockPos origin = attachment.assembly().origin();
        double x = origin.getX() + 0.5 - hostCenter.x();
        double y = origin.getY() + 0.5 - hostCenter.y();
        double z = origin.getZ() + 0.5 - hostCenter.z();

        Rapier3D.setKinematicContraptionTransform(
                Rapier3D.getID(level),
                binding.proxyRuntimeId(),
                new double[]{0.5, 0.5, 0.5},
                new double[]{x, y, z, 0.0, 0.0, 0.0, 1.0},
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0});
    }

    private static void suppressChildSolverCollider(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment) {
        BlockPos sentinelLocal = attachment.assembly().serviceAnchor();
        BlockPos sentinelGlobal = MechanismSubLevelService.toPlotPosition(
                attachment.logicalBody(),
                sentinelLocal);
        Rapier3D.setLocalBounds(
                Rapier3D.getID(level),
                Rapier3D.getID(attachment.logicalBody()),
                sentinelGlobal.getX(),
                sentinelGlobal.getY(),
                sentinelGlobal.getZ(),
                sentinelGlobal.getX(),
                sentinelGlobal.getY(),
                sentinelGlobal.getZ());
    }

    private static void removeBinding(
            ServerLevel level,
            PhysicsPipeline pipeline,
            Binding binding,
            boolean restoreChild) {
        if (binding == null) {
            return;
        }

        try {
            if (Rapier3D.ENABLED) {
                Rapier3D.removeKinematicContraption(
                        Rapier3D.getID(level),
                        binding.proxyRuntimeId());
            }
        } catch (RuntimeException | LinkageError exception) {
            AntikytheraMechanism.LOGGER.debug(
                    "Could not remove hosted mini collision proxy {}",
                    binding.proxyRuntimeId(),
                    exception);
        }

        ServerSubLevel child = binding.child();
        if (restoreChild && child != null && !child.isRemoved()) {
            try {
                // Route restoration through the public pipeline so Sable Scale can reapply the
                // managed child's 0.5-scale collider bounds.
                pipeline.onStatsChanged(child);
            } catch (RuntimeException exception) {
                AntikytheraMechanism.LOGGER.warn(
                        "Could not restore managed child collider bounds for {}",
                        child.getUniqueId(),
                        exception);
            }
        }
    }

    private static long stateSignature(
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, attachment.assembly().orientation().hashCode());

        for (BlockPos frame : sortedFrames(attachment.assembly())) {
            BlockPos offset = frame.subtract(attachment.assembly().origin());
            hash = mix(hash, offset.getX());
            hash = mix(hash, offset.getY());
            hash = mix(hash, offset.getZ());

            for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                    for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                                attachment.assembly(), frame, x, y, z);
                        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(
                                attachment.logicalBody(), miniLocal);
                        hash = mix(hash, level.getBlockState(miniGlobal).hashCode());
                    }
                }
            }
        }
        return hash;
    }

    private static List<BlockPos> sortedFrames(MechanismAssembly assembly) {
        return assembly.frameMask().frames().stream()
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .toList();
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static int pack(RapierVoxelColliderData colliderData) {
        int colliderValue = colliderData.handle() + 1;
        return (colliderValue << 16)
                | (VoxelNeighborhoodState.CORNER.byteRepresentation() & 0xFF);
    }

    record BoxKey(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double volume() {
            return Math.max(0.0, maxX - minX)
                    * Math.max(0.0, maxY - minY)
                    * Math.max(0.0, maxZ - minZ);
        }
    }

    private record FrameColliderKey(
            List<BoxKey> boxes,
            long frictionBits,
            long volumeBits,
            long restitutionBits) {
    }

    private record FrameGeometry(
            List<BoxKey> boxes,
            double friction,
            double buoyancyVolume,
            double restitution,
            FrameColliderKey key) {
    }

    private static final class Binding {
        private final int proxyRuntimeId;
        private final int childRuntimeId;
        private final int hostRuntimeId;
        private long stateSignature;
        private long lastGeometryCheckGameTime;
        private final ServerSubLevel child;

        private Binding(
                int proxyRuntimeId,
                int childRuntimeId,
                int hostRuntimeId,
                long stateSignature,
                long lastGeometryCheckGameTime,
                ServerSubLevel child) {
            this.proxyRuntimeId = proxyRuntimeId;
            this.childRuntimeId = childRuntimeId;
            this.hostRuntimeId = hostRuntimeId;
            this.stateSignature = stateSignature;
            this.lastGeometryCheckGameTime = lastGeometryCheckGameTime;
            this.child = child;
        }

        int proxyRuntimeId() {
            return proxyRuntimeId;
        }

        int childRuntimeId() {
            return childRuntimeId;
        }

        int hostRuntimeId() {
            return hostRuntimeId;
        }

        long stateSignature() {
            return stateSignature;
        }

        void stateSignature(long value) {
            stateSignature = value;
        }

        long lastGeometryCheckGameTime() {
            return lastGeometryCheckGameTime;
        }

        void lastGeometryCheckGameTime(long value) {
            lastGeometryCheckGameTime = value;
        }

        ServerSubLevel child() {
            return child;
        }
    }
}
