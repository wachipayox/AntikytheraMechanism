package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.api.physics.MiniPhysicsEffectRegistry;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockCluster;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockMaterial;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Feeds managed mini floating materials into their physical host's native Sable calculation. */
public final class HostedMiniFloatingMaterialBridge {
    private static final ThreadLocal<Map<ServerSubLevel, List<FloatingClusterContainer>>> PREPARED =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private HostedMiniFloatingMaterialBridge() {
    }

    public static List<FloatingClusterContainer> contributionFor(ServerSubLevel host) {
        if (host == null || host.isRemoved() || MechanismSubLevelService.getOwnerAssemblyId(host) != null) {
            return List.of();
        }
        Map<ServerSubLevel, List<FloatingClusterContainer>> prepared = PREPARED.get();
        return prepared.computeIfAbsent(host, HostedMiniFloatingMaterialBridge::buildContribution);
    }

    public static boolean hasContribution(ServerSubLevel host) {
        return !contributionFor(host).isEmpty();
    }

    public static void clear(ServerSubLevel host) {
        Map<ServerSubLevel, List<FloatingClusterContainer>> prepared = PREPARED.get();
        prepared.remove(host);
        if (prepared.isEmpty()) {
            PREPARED.remove();
        }
    }

    private static List<FloatingClusterContainer> buildContribution(ServerSubLevel host) {
        ServerLevel level = host.getLevel();
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        FloatingClusterContainer synthetic = new FloatingClusterContainer();

        for (MechanismAssembly assembly : manager.assemblies()) {
            if (manager.isContentRecoveryLocked(assembly.id())
                    || manager.pendingPistonMove(assembly.id()).isPresent()
                    || manager.pendingContraptionMove(assembly.id()).isPresent()
                    || manager.pendingFrameEvacuation(assembly.id()).isPresent()) {
                continue;
            }
            MechanismAssemblyHost.Resolution resolution = MechanismAssemblyHost.resolve(level, assembly.origin());
            if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                    || resolution.subLevel() == null
                    || !host.getUniqueId().equals(resolution.subLevel().getUniqueId())) {
                continue;
            }
            ServerSubLevel child = MechanismSubLevelService.get(level, assembly);
            if (child != null && !child.isRemoved()) {
                collectAssembly(level, host, child, assembly, synthetic);
            }
        }
        return synthetic.clusters.isEmpty() ? List.of() : List.of(synthetic);
    }

    private static void collectAssembly(
            ServerLevel level,
            ServerSubLevel host,
            ServerSubLevel child,
            MechanismAssembly assembly,
            FloatingClusterContainer target) {
        Vector3d hostCenterOfMass = new Vector3d(host.getMassTracker().getCenterOfMass());
        for (BlockPos frame : assembly.frames()) {
            for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                    for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                        BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
                        if (!level.hasChunkAt(global)) continue;

                        BlockState state = level.getChunkAt(global).getBlockState(global);
                        FloatingBlockMaterial material = PhysicsBlockPropertyHelper.getFloatingMaterial(state);
                        if (material == null) continue;

                        double scale = PhysicsBlockPropertyHelper.getFloatingScale(state)
                                * MiniPhysicsEffectRegistry.MINI_VOLUME_SCALE;
                        if (!Double.isFinite(scale) || scale <= 0.0) continue;

                        Vector3d childCenter = new Vector3d(global.getX() + 0.5, global.getY() + 0.5, global.getZ() + 0.5);
                        Vector3d worldCenter = child.logicalPose().transformPosition(childCenter, new Vector3d());
                        Vector3d hostCenter = host.logicalPose().transformPositionInverse(worldCenter, new Vector3d());
                        hostCenter.sub(hostCenterOfMass);

                        findOrCreateCluster(target, material).getBlockData().addFloatingBlock(hostCenter, scale);
                    }
                }
            }
        }
    }

    private static FloatingBlockCluster findOrCreateCluster(
            FloatingClusterContainer container,
            FloatingBlockMaterial material) {
        for (FloatingBlockCluster cluster : container.clusters) {
            if (cluster.getMaterial().equals(material)) return cluster;
        }
        FloatingBlockCluster cluster = new FloatingBlockCluster(material);
        container.clusters.add(cluster);
        return cluster;
    }
}
