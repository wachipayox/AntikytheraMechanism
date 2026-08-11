package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only projection of directly adjacent parent-world blocks into the mini world's exterior.
 * Nothing is copied into the Sable plot: no duplicate BlockEntities, ticking, drops or rendering.
 */
public final class MiniWorldEnvironment {
    private static final double WORLD_ALIGNED_EPSILON = 1.0E-5;

    private MiniWorldEnvironment() {
    }

    public static @Nullable BlockState virtualBlockState(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return null;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(ownerId)
                || manager.pendingPistonMove(ownerId).isPresent()
                || manager.pendingContraptionMove(ownerId).isPresent()) {
            return null;
        }

        // Parent BlockPos projection is meaningful only while the assembly is aligned to the parent grid.
        // Rotated Create contraptions intentionally get air outside their FrameMask rather than a false mapping.
        if (!assembly.poseTarget().approximatelyEquals(
                AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
            return null;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)
                || miniPosition.equals(assembly.serviceAnchor())) {
            return null;
        }

        // Physical plot content, including service-shell ports, always wins over virtual context.
        if (!level.hasChunkAt(globalPlotPosition)
                || !level.getChunkAt(globalPlotPosition).getBlockState(globalPlotPosition).isAir()) {
            return null;
        }

        BlockPos parentPosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!touchesAssemblyFace(assembly, parentPosition) || !level.hasChunkAt(parentPosition)) {
            return null;
        }

        BlockState parentState = level.getChunkAt(parentPosition).getBlockState(parentPosition);
        if (parentState.isAir() || parentState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        ResourceLocation parentId = BuiltInRegistries.BLOCK.getKey(parentState.getBlock());
        if (parentId != null && AntikytheraMechanism.MOD_ID.equals(parentId.getNamespace())) {
            return null;
        }
        return parentState;
    }

    /** Notify mini boundary blocks when one of their projected parent neighbours changes. */
    public static void parentBlockChanged(ServerLevel level, BlockPos parentPosition) {
        if (Sable.HELPER.getContaining(level, parentPosition) != null) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> visited = new HashSet<>();
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = parentPosition.relative(directionToFrame);
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null || !visited.add(assembly.id())) {
                continue;
            }
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null
                    || !assembly.poseTarget().approximatelyEquals(
                            AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
                continue;
            }

            Direction boundary = directionToFrame.getOpposite();
            BlockState parentState = level.getChunkAt(parentPosition).getBlockState(parentPosition);
            for (BlockPos local : boundaryCells(assembly, framePosition, boundary)) {
                BlockPos shellLocal = local.relative(boundary);
                BlockPos shellGlobal = MechanismSubLevelService.toPlotPosition(subLevel, shellLocal);
                if (level.hasChunkAt(shellGlobal)) {
                    level.updateNeighborsAt(shellGlobal, parentState.getBlock());
                }
            }
        }
    }

    private static Set<BlockPos> boundaryCells(
            MechanismAssembly assembly, BlockPos framePosition, Direction boundary) {
        Set<BlockPos> cells = new HashSet<>(4);
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                int x = a;
                int y = b;
                int z = 0;
                switch (boundary.getAxis()) {
                    case X -> {
                        x = boundary == Direction.WEST ? 0 : 1;
                        y = a;
                        z = b;
                    }
                    case Y -> {
                        x = a;
                        y = boundary == Direction.DOWN ? 0 : 1;
                        z = b;
                    }
                    case Z -> {
                        x = a;
                        y = b;
                        z = boundary == Direction.NORTH ? 0 : 1;
                    }
                }
                cells.add(MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z));
            }
        }
        return cells;
    }

    private static boolean touchesAssemblyFace(MechanismAssembly assembly, BlockPos parentPosition) {
        for (Direction direction : Direction.values()) {
            if (assembly.containsFrame(parentPosition.relative(direction))) {
                return true;
            }
        }
        return false;
    }
}
