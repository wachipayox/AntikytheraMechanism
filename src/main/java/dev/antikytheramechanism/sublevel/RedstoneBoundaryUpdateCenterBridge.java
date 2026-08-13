package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Mirrors the bounded update centres deliberately emitted by vanilla redstone across a Frame. */
public final class RedstoneBoundaryUpdateCenterBridge {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;

    private RedstoneBoundaryUpdateCenterBridge() {
    }

    public static void mirror(ServerLevel level, BlockPos updateCenter, Block sourceBlock) {
        if (sourceBlock == null || !sourceBlock.defaultBlockState().isSignalSource()) {
            return;
        }
        if (mirrorManagedBoundaryCenterToHost(level, updateCenter, sourceBlock)) {
            return;
        }
        mirrorFrameCenterToMini(level, updateCenter, sourceBlock);
    }

    private static boolean mirrorManagedBoundaryCenterToHost(
            ServerLevel level,
            BlockPos boundaryGlobal,
            Block sourceBlock) {
        SubLevel containing = Sable.HELPER.getContaining(level, boundaryGlobal);
        if (!(containing instanceof ServerSubLevel child)) {
            return false;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (ownerId == null) {
            return false;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        if (assembly == null
                || !child.getUniqueId().equals(assembly.subLevelId())
                || manager.isContentRecoveryLocked(ownerId)
                || manager.pendingPistonMove(ownerId).isPresent()
                || manager.pendingContraptionMove(ownerId).isPresent()
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return false;
        }

        BlockPos boundaryMini = boundaryGlobal.subtract(child.getPlot().getCenterBlock());
        if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, boundaryMini)
                || !touchesOwnedMiniCell(assembly, boundaryMini)) {
            return false;
        }

        if (!level.hasChunkAt(boundaryGlobal)
                || !level.getChunkAt(boundaryGlobal).getBlockState(boundaryGlobal).isAir()) {
            return false;
        }

        BlockPos hostPosition = MiniCoordinateMapper.miniToFrame(assembly, boundaryMini);
        if (!level.hasChunkAt(hostPosition)
                || !MechanismAssemblyHost.samePhysicalHost(level, assembly, hostPosition)) {
            return false;
        }
        if (level.getChunkAt(hostPosition)
                .getBlockState(hostPosition)
                .is(ModRegistries.MECHANISM_FRAME.get())) {
            return false;
        }

        level.updateNeighborsAt(hostPosition, sourceBlock);
        return true;
    }

    private static void mirrorFrameCenterToMini(
            ServerLevel level,
            BlockPos framePosition,
            Block sourceBlock) {
        if (!level.hasChunkAt(framePosition)
                || !level.getChunkAt(framePosition)
                        .getBlockState(framePosition)
                        .is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return;
        }

        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null || child.isRemoved()) {
            return;
        }

        Set<BlockPos> miniUpdateCenters = new HashSet<>(8);
        for (Direction physicalOutward : Direction.values()) {
            BlockPos parentPosition = framePosition.relative(physicalOutward);
            if (assembly.containsFrame(parentPosition)
                    || !level.hasChunkAt(parentPosition)
                    || !MechanismAssemblyHost.samePhysicalHost(level, assembly, parentPosition)) {
                continue;
            }

            BlockState parentState = level.getChunkAt(parentPosition).getBlockState(parentPosition);
            if (!parentState.is(sourceBlock)) {
                continue;
            }

            Direction logicalOutward = assembly.orientation().toLogical(physicalOutward);
            for (int a = 0; a < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; a++) {
                for (int b = 0; b < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; b++) {
                    BlockPos local = boundaryMiniPosition(assembly, framePosition, logicalOutward, a, b);
                    BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                    if (level.hasChunkAt(global)) {
                        miniUpdateCenters.add(global.immutable());
                    }
                }
            }
        }

        if (miniUpdateCenters.isEmpty()) {
            return;
        }

        MiniWorldEnvironment.withVirtualReads(() -> {
            for (BlockPos miniCenter : miniUpdateCenters) {
                level.updateNeighborsAt(miniCenter, sourceBlock);
            }
        });
    }

    private static boolean touchesOwnedMiniCell(MechanismAssembly assembly, BlockPos boundaryMini) {
        for (Direction direction : Direction.values()) {
            if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, boundaryMini.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos boundaryMiniPosition(
            MechanismAssembly assembly,
            BlockPos framePosition,
            Direction logicalBoundary,
            int a,
            int b) {
        int x = a;
        int y = b;
        int z = 0;
        switch (logicalBoundary.getAxis()) {
            case X -> {
                x = logicalBoundary == Direction.WEST ? 0 : MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logicalBoundary == Direction.DOWN ? 0 : MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logicalBoundary == Direction.NORTH ? 0 : MiniCoordinateMapper.CELLS_PER_FRAME_AXIS - 1;
            }
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
    }
}
