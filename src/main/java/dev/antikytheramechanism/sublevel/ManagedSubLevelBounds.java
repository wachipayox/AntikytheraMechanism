package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Keeps an empty managed Sable SubLevel alive using metadata-only bounds that match its FrameMask.
 * No hidden block, collision shape, tick or renderable is placed in the plot.
 */
public final class ManagedSubLevelBounds {
    private ManagedSubLevelBounds() {
    }

    public static boolean preserveIfEmpty(ServerSubLevel subLevel) {
        BoundingBox3ic current = subLevel.getPlot().getBoundingBox();
        if (current != BoundingBox3i.EMPTY && current.volume() > 0) {
            return false;
        }
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return false;
        }
        if (!(subLevel.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        return assembly != null && ensureEmptyBounds(subLevel, assembly);
    }

    /** Ensures a content-empty plot has a non-empty broadphase box exactly covering its frames. */
    public static boolean ensureEmptyBounds(ServerSubLevel subLevel, MechanismAssembly assembly) {
        if (assembly.frames().isEmpty()) {
            return false;
        }

        BoundingBox3ic current = subLevel.getPlot().getBoundingBox();
        if (current != BoundingBox3i.EMPTY && current.volume() > 0 && containsPhysicalContent(subLevel, current)) {
            return true;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPos frame : assembly.frames()) {
            BlockPos offset = frame.subtract(assembly.origin());
            int frameMiniX = offset.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            int frameMiniY = offset.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            int frameMiniZ = offset.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            minX = Math.min(minX, frameMiniX);
            minY = Math.min(minY, frameMiniY);
            minZ = Math.min(minZ, frameMiniZ);
            maxX = Math.max(maxX, frameMiniX + MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1);
            maxY = Math.max(maxY, frameMiniY + MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1);
            maxZ = Math.max(maxZ, frameMiniZ + MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1);
        }

        BlockPos center = subLevel.getPlot().getCenterBlock();
        subLevel.getPlot().setBoundingBox(new BoundingBox3i(
                center.getX() + minX,
                center.getY() + minY,
                center.getZ() + minZ,
                center.getX() + maxX,
                center.getY() + maxY,
                center.getZ() + maxZ));
        subLevel.updateBoundingBox();
        return true;
    }

    /**
     * A synthetic empty bound can itself be non-empty, so check the actual block states before
     * deciding whether it should be replaced by a refreshed FrameMask bound.
     */
    private static boolean containsPhysicalContent(ServerSubLevel subLevel, BoundingBox3ic bounds) {
        ServerLevel level = subLevel.getLevel();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (level.hasChunkAt(pos) && !level.getChunkAt(pos).getBlockState(pos).isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
