package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.Rapier3DInvoker;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.collider.SableCollisionContext;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3dc;
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
 * that pose-driven child is erased by {@link AssemblyPoseDriver}. Sable 2.0.3 already represents
 * mounted geometry as zero-density LevelColliders parented to an existing rigid body. This bridge
 * creates the same native representation through the narrow, version-pinned
 * {@link Rapier3DInvoker} boundary.</p>
 *
 * <p>Each physical Frame is represented by one synthetic host-local voxel. The eight logical
 * 0.5-scale mini cells inside that Frame are baked into that voxel as collision boxes after applying
 * {@link FrameOrientation}. The mounted collider therefore contributes geometry but no mass;
 * {@link HostedMiniMassBridge} remains the single authority for projected mini mass/inertia.</p>
 *
 * <p>Native proxy state is deliberately sticky. Rapier marks an attached collider as changed every
 * time {@code set_position_wrt_parent} is called, even when the supplied pose is identical. Rewriting
 * the proxy pose (and replacing the managed child's sentinel shape) every physics substep prevents a
 * resting contact manifold from becoming stable. We therefore upload only real state changes, just as
 * Sable's own kinematic-contraption path does.</p>
 */
public final class HostedMiniCollisionBridge {
    private static final double HALF = MiniCoordinateMapper.SUBLEVEL_SCALE;
    private static final double DEFAULT_FRICTION = 1.0;
    private static final double DEFAULT_RESTITUTION = 0.0;
    private static final double TRANSFORM_EPSILON = 1.0E-7;

    private static final Map<ServerLevel, Map<UUID, Binding>> BINDINGS = new WeakHashMap<>();
    private static final Map<FrameColliderKey, Integer> COLLIDER_CACHE = new HashMap<>();

    private HostedMiniCollisionBridge() {
    }

    /** Reconciles all FOREIGN-hosted managed children immediately before one Sable physics substep. */
    public static void reconcile(
            ServerLevel level,
            PhysicsPipeline pipeline,
            MechanismAssemblyManager manager) {
        final long sceneHandle;
        try {
            sceneHandle = Rapier3DInvoker.antikytheramechanism$getSceneHandle(level);
        } catch (IllegalStateException exception) {
            // Hosted collision projection is specific to Sable's bundled Rapier backend. If a
            // different pipeline is selected, leave the managed child on Sable's normal path.
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
                removeBinding(sceneHandle, pipeline, bindings.remove(assembly.id()), true);
                continue;
            }

            HostedMiniPhysicalAttachment.Attachment attachment =
                    HostedMiniPhysicalAttachment.resolve(level, assembly, child);
            if (attachment == null) {
                removeBinding(sceneHandle, pipeline, bindings.remove(assembly.id()), true);
                continue;
            }

            try {
                Binding binding = bindings.get(assembly.id());
                if (binding == null
                        || binding.childRuntimeId() != child.getRuntimeId()
                        || binding.hostRuntimeId() != attachment.physicalBody().getRuntimeId()) {
                    removeBinding(sceneHandle, pipeline, binding, true);
                    binding = createBinding(sceneHandle, pipeline, level, attachment);
                    bindings.put(assembly.id(), binding);
                }

                boolean geometryRebuilt = false;
                long gameTime = level.getGameTime();
                if (binding.lastGeometryCheckGameTime() != gameTime) {
                    long signature = stateSignature(level, attachment);
                    binding.lastGeometryCheckGameTime(gameTime);
                    if (signature != binding.stateSignature()) {
                        rebuild(sceneHandle, level, attachment, binding, signature);
                        geometryRebuilt = true;
                    }
                }

                // A rigidly mounted proxy must be quiet when its host-local transform is unchanged.
                // Sable's normal contraption path uses the same 1e-7 threshold before touching Rapier.
                updateMountedTransform(sceneHandle, attachment, binding, false);

                // Only suppress the child's own solver collider after the mounted proxy exists.
                // Re-assert the sentinel only when child MassData/bounds changed (which can make
                // Sable call onStatsChanged and restore its regular collider) or after a rebuild.
                suppressChildSolverCollider(sceneHandle, attachment, binding, geometryRebuilt);
            } catch (RuntimeException | LinkageError exception) {
                Binding failed = bindings.remove(assembly.id());
                removeBinding(sceneHandle, pipeline, failed, true);
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
            removeBinding(sceneHandle, pipeline, bindings.remove(id), false);
        }

        if (bindings.isEmpty()) {
            BINDINGS.remove(level);
        }
    }

    static int activeProxyCount(ServerLevel level) {
        Map<UUID, Binding> bindings = BINDINGS.get(level);
        return bindings == null ? 0 : bindings.size();
    }

    static NativeMutationCounts nativeMutationCounts(ServerLevel level, UUID assemblyId) {
        Map<UUID, Binding> bindings = BINDINGS.get(level);
        Binding binding = bindings == null ? null : bindings.get(assemblyId);
        return binding == null
                ? new NativeMutationCounts(0L, 0L)
                : new NativeMutationCounts(
                        binding.transformUploadCount(),
                        binding.childBoundsUploadCount());
    }

    private static Binding createBinding(
            long sceneHandle,
            PhysicsPipeline pipeline,
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment) {
        int proxyId = pipeline.getNextRuntimeID();
        int hostId = attachment.physicalBody().getRuntimeId();

        Rapier3DInvoker.antikytheramechanism$createKinematicContraption(
                sceneHandle,
                hostId,
                proxyId,
                identityPose());

        Binding binding = new Binding(
                proxyId,
                attachment.logicalBody().getRuntimeId(),
                hostId,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                attachment.logicalBody());
        rebuild(sceneHandle, level, attachment, binding, stateSignature(level, attachment));
        return binding;
    }

    private static void rebuild(
            long sceneHandle,
            ServerLevel level,
            HostedMiniPhysicalAttachment.Attachment attachment,
            Binding binding,
            long signature) {
        // Sable 2.0.3 has no per-section removal operation for mounted LevelColliders. Recreate the
        // zero-density proxy with the same runtime ID so removed mini blocks cannot leave stale
        // sections behind.
        Rapier3DInvoker.antikytheramechanism$removeKinematicContraption(
                sceneHandle,
                binding.proxyRuntimeId());
        Rapier3DInvoker.antikytheramechanism$createKinematicContraption(
                sceneHandle,
                binding.hostRuntimeId(),
                binding.proxyRuntimeId(),
                identityPose());
        binding.clearMountedTransform();

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

            int colliderHandle = COLLIDER_CACHE.computeIfAbsent(
                    geometry.key(),
                    ignored -> createColliderData(geometry));

            SectionPos sectionPos = SectionPos.of(physicalOffset);
            int[] section = sections.computeIfAbsent(
                    sectionPos.asLong(),
                    ignored -> new int[LevelChunkSection.SECTION_SIZE]);
            int index = (physicalOffset.getX() & 15)
                    + ((physicalOffset.getZ() & 15) << 4)
                    + ((physicalOffset.getY() & 15) << 8);
            section[index] = pack(colliderHandle);
        }

        // The collider was just recreated, so its transform must be uploaded even if numerically
        // equal to the previous instance's transform.
        updateMountedTransform(sceneHandle, attachment, binding, true);
        for (Map.Entry<Long, int[]> entry : sections.entrySet()) {
            SectionPos sectionPos = SectionPos.of(entry.getKey());
            Rapier3DInvoker.antikytheramechanism$addKinematicContraptionChunkSection(
                    sceneHandle,
                    binding.proxyRuntimeId(),
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    entry.getValue());
        }
        Rapier3DInvoker.antikytheramechanism$setLocalBounds(
                sceneHandle,
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

    private static int createColliderData(FrameGeometry geometry) {
        // Mounted LevelColliders are zero-density in Sable's native backend. The volume stored on
        // the collider is Sable metadata only; HostedMiniMassBridge remains the mass authority.
        int handle = Rapier3DInvoker.antikytheramechanism$newVoxelCollider(
                geometry.friction(),
                geometry.buoyancyVolume(),
                geometry.restitution(),
                false,
                null);
        for (BoxKey box : geometry.boxes()) {
            Rapier3DInvoker.antikytheramechanism$addVoxelColliderBox(
                    handle,
                    new double[]{
                            box.minX(), box.minY(), box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ()
                    });
        }
        return handle;
    }

    private static void updateMountedTransform(
            long sceneHandle,
            HostedMiniPhysicalAttachment.Attachment attachment,
            Binding binding,
            boolean force) {
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

        if (!force && binding.matchesMountedTransform(x, y, z)) {
            return;
        }

        Rapier3DInvoker.antikytheramechanism$setKinematicContraptionTransform(
                sceneHandle,
                binding.proxyRuntimeId(),
                new double[]{0.5, 0.5, 0.5},
                new double[]{x, y, z, 0.0, 0.0, 0.0, 1.0},
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0});
        binding.recordMountedTransform(x, y, z);
    }

    private static void suppressChildSolverCollider(
            long sceneHandle,
            HostedMiniPhysicalAttachment.Attachment attachment,
            Binding binding,
            boolean force) {
        long signature = childSolverStateSignature(attachment.logicalBody());
        if (!force && binding.childColliderSuppressed()
                && binding.lastChildSolverStateSignature() == signature) {
            return;
        }

        BlockPos sentinelLocal = attachment.assembly().serviceAnchor();
        BlockPos sentinelGlobal = MechanismSubLevelService.toPlotPosition(
                attachment.logicalBody(),
                sentinelLocal);
        Rapier3DInvoker.antikytheramechanism$setLocalBounds(
                sceneHandle,
                attachment.logicalBody().getRuntimeId(),
                sentinelGlobal.getX(),
                sentinelGlobal.getY(),
                sentinelGlobal.getZ(),
                sentinelGlobal.getX(),
                sentinelGlobal.getY(),
                sentinelGlobal.getZ());
        binding.recordChildSuppression(signature);
    }

    private static long childSolverStateSignature(ServerSubLevel child) {
        long hash = 0xcbf29ce484222325L;
        if (child.getMassTracker() != null) {
            hash = mix(hash, Double.doubleToLongBits(child.getMassTracker().getMass()));
            Vector3dc center = child.getMassTracker().getCenterOfMass();
            if (center != null) {
                hash = mix(hash, Double.doubleToLongBits(center.x()));
                hash = mix(hash, Double.doubleToLongBits(center.y()));
                hash = mix(hash, Double.doubleToLongBits(center.z()));
            }
            Matrix3dc inertia = child.getMassTracker().getInertiaTensor();
            hash = mix(hash, Double.doubleToLongBits(inertia.m00()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m01()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m02()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m10()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m11()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m12()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m20()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m21()));
            hash = mix(hash, Double.doubleToLongBits(inertia.m22()));
        }

        var bounds = child.getPlot().getBoundingBox();
        hash = mix(hash, bounds.minX());
        hash = mix(hash, bounds.minY());
        hash = mix(hash, bounds.minZ());
        hash = mix(hash, bounds.maxX());
        hash = mix(hash, bounds.maxY());
        hash = mix(hash, bounds.maxZ());
        return hash;
    }

    private static void removeBinding(
            long sceneHandle,
            PhysicsPipeline pipeline,
            Binding binding,
            boolean restoreChild) {
        if (binding == null) {
            return;
        }

        try {
            Rapier3DInvoker.antikytheramechanism$removeKinematicContraption(
                    sceneHandle,
                    binding.proxyRuntimeId());
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
        return mix(hash, (long) value);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static int pack(int colliderHandle) {
        int colliderValue = colliderHandle + 1;
        return (colliderValue << 16)
                | (VoxelNeighborhoodState.CORNER.byteRepresentation() & 0xFF);
    }

    private static double[] identityPose() {
        return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0};
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

    record NativeMutationCounts(long transformUploads, long childBoundsUploads) {
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
        private boolean hasMountedTransform;
        private double lastMountedX;
        private double lastMountedY;
        private double lastMountedZ;
        private boolean childColliderSuppressed;
        private long lastChildSolverStateSignature = Long.MIN_VALUE;
        private long transformUploadCount;
        private long childBoundsUploadCount;

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

        boolean matchesMountedTransform(double x, double y, double z) {
            if (!hasMountedTransform) {
                return false;
            }
            double dx = x - lastMountedX;
            double dy = y - lastMountedY;
            double dz = z - lastMountedZ;
            return dx * dx + dy * dy + dz * dz
                    <= TRANSFORM_EPSILON * TRANSFORM_EPSILON;
        }

        void recordMountedTransform(double x, double y, double z) {
            hasMountedTransform = true;
            lastMountedX = x;
            lastMountedY = y;
            lastMountedZ = z;
            transformUploadCount++;
        }

        void clearMountedTransform() {
            hasMountedTransform = false;
        }

        boolean childColliderSuppressed() {
            return childColliderSuppressed;
        }

        long lastChildSolverStateSignature() {
            return lastChildSolverStateSignature;
        }

        void recordChildSuppression(long signature) {
            childColliderSuppressed = true;
            lastChildSolverStateSignature = signature;
            childBoundsUploadCount++;
        }

        long transformUploadCount() {
            return transformUploadCount;
        }

        long childBoundsUploadCount() {
            return childBoundsUploadCount;
        }
    }
}
