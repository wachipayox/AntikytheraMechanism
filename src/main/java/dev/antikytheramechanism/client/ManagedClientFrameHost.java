package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** Resolves the physical foreign Sable host and origin Frame of a managed client child. */
public final class ManagedClientFrameHost {
    private static final String MANAGED_NAME_PREFIX = "antikythera-";
    private static final double SCALE_EPSILON = 1.0E-6;
    private static final double MAX_ORIGIN_DRIFT_SQUARED = 0.35 * 0.35;

    private ManagedClientFrameHost() {
    }

    public static @Nullable Binding resolve(ClientSubLevel child) {
        if (!ManagedClientSubLevelIdentity.isManaged(child)) {
            return null;
        }
        UUID assemblyId = assemblyId(child);
        if (assemblyId == null) {
            return null;
        }

        Level level = child.getLevel();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        BlockPos childPlotCenter = child.getPlot().getCenterBlock();
        Vector3d childOriginCenter = new Vector3d(
                childPlotCenter.getX() + 1.0,
                childPlotCenter.getY() + 1.0,
                childPlotCenter.getZ() + 1.0);
        Vector3d worldOriginCenter = child.logicalPose().transformPosition(childOriginCenter, new Vector3d());

        Binding match = null;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ClientSubLevel host)
                    || host == child
                    || host.isRemoved()
                    || ManagedClientSubLevelIdentity.isManaged(host)
                    || !hasUnitScale(host)) {
                continue;
            }

            Vector3d expectedHostCenter = host.logicalPose()
                    .transformPositionInverse(worldOriginCenter, new Vector3d());
            FrameMatch frame = findOriginFrame(level, host, expectedHostCenter, assemblyId);
            if (frame == null) {
                continue;
            }
            if (match != null && match.host() != host) {
                return null;
            }
            match = new Binding(child, host, assemblyId, frame.position(), frame.orientation());
        }
        return match;
    }

    private static @Nullable FrameMatch findOriginFrame(
            Level level,
            ClientSubLevel expectedHost,
            Vector3dc expectedCenter,
            UUID assemblyId) {
        BlockPos nearest = BlockPos.containing(expectedCenter.x(), expectedCenter.y(), expectedCenter.z());
        FrameMatch best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        // The semantic origin is encoded explicitly in the synchronized BE mapping. Search a tiny
        // neighbourhood instead of demanding sub-millimetre pose equality from two physics bodies.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos frame = nearest.offset(dx, dy, dz);
                    if (Sable.HELPER.getContainingClient(frame) != expectedHost
                            || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())
                            || !(level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity frameEntity)
                            || !assemblyId.equals(frameEntity.getAssemblyId())
                            || !BlockPos.ZERO.equals(frameEntity.getLogicalFrameOffset())) {
                        continue;
                    }
                    double distance = distanceSquaredToCenter(expectedCenter, frame);
                    if (distance > MAX_ORIGIN_DRIFT_SQUARED || distance >= bestDistance) {
                        continue;
                    }
                    bestDistance = distance;
                    best = new FrameMatch(frame.immutable(), frameEntity.getFrameOrientation());
                }
            }
        }
        return best;
    }

    private static double distanceSquaredToCenter(Vector3dc expected, BlockPos frame) {
        double dx = expected.x() - (frame.getX() + 0.5);
        double dy = expected.y() - (frame.getY() + 0.5);
        double dz = expected.z() - (frame.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static @Nullable UUID assemblyId(ClientSubLevel child) {
        String name = child.getName();
        if (name == null || !name.startsWith(MANAGED_NAME_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(MANAGED_NAME_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean hasUnitScale(SubLevel subLevel) {
        Vector3dc scale = subLevel.logicalPose().scale();
        return Math.abs(scale.x() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.y() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.z() - 1.0) <= SCALE_EPSILON;
    }

    public record Binding(
            ClientSubLevel child,
            ClientSubLevel host,
            UUID assemblyId,
            BlockPos originFrame,
            FrameOrientation orientation) {

        public boolean isStillValid() {
            if (child.isRemoved() || host.isRemoved() || child.getLevel() != host.getLevel()) {
                return false;
            }
            Level level = child.getLevel();
            return Sable.HELPER.getContainingClient(originFrame) == host
                    && level.getBlockState(originFrame).is(ModRegistries.MECHANISM_FRAME.get())
                    && level.getBlockEntity(originFrame) instanceof MechanismFrameBlockEntity frameEntity
                    && assemblyId.equals(frameEntity.getAssemblyId())
                    && BlockPos.ZERO.equals(frameEntity.getLogicalFrameOffset())
                    && orientation.equals(frameEntity.getFrameOrientation());
        }

        public BlockPos frameForChildPlotBlock(BlockPos childPlotBlock) {
            BlockPos mini = childPlotBlock.subtract(child.getPlot().getCenterBlock());
            BlockPos logicalOffset = new BlockPos(
                    Math.floorDiv(mini.getX(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                    Math.floorDiv(mini.getY(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                    Math.floorDiv(mini.getZ(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS));
            BlockPos frame = originFrame.offset(orientation.toPhysical(logicalOffset));
            Level level = child.getLevel();
            if (Sable.HELPER.getContainingClient(frame) == host
                    && level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())
                    && level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity frameEntity
                    && assemblyId.equals(frameEntity.getAssemblyId())
                    && logicalOffset.equals(frameEntity.getLogicalFrameOffset())) {
                return frame.immutable();
            }
            return originFrame;
        }
    }

    private record FrameMatch(BlockPos position, FrameOrientation orientation) {
    }
}
