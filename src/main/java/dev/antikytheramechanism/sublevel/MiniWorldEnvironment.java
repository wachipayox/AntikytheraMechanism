package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Read-only projection of directly adjacent host blocks into immutable logical mini axes. */
public final class MiniWorldEnvironment {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;
    private static final String MANAGED_NAME_PREFIX = "antikythera-";
    private static final ThreadLocal<Integer> VIRTUAL_READ_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> EXTERNAL_RAIL_ISOLATION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<CrossFrameVirtualSupport> CROSS_FRAME_VIRTUAL_SUPPORT = new ThreadLocal<>();

    private MiniWorldEnvironment() {}

    public static boolean isManagedSubLevel(@Nullable SubLevel subLevel) {
        return subLevel != null && subLevel.getName() != null && subLevel.getName().startsWith(MANAGED_NAME_PREFIX);
    }

    public static boolean isManagedMiniPosition(Level level, BlockPos position) {
        return isManagedSubLevel(Sable.HELPER.getContaining(level, position));
    }

    public static boolean shouldUseVirtualReads(Level level, BlockPos position) {
        return level instanceof ServerLevel && isManagedMiniPosition(level, position);
    }

    public static <T> T withVirtualReads(Supplier<T> action) {
        int previous = VIRTUAL_READ_DEPTH.get();
        VIRTUAL_READ_DEPTH.set(previous + 1);
        try { return action.get(); }
        finally {
            if (previous == 0) VIRTUAL_READ_DEPTH.remove(); else VIRTUAL_READ_DEPTH.set(previous);
        }
    }

    public static void withVirtualReads(Runnable action) {
        withVirtualReads(() -> { action.run(); return null; });
    }

    /**
     * Exposes one source mini block as a read-only synthetic support inside a different managed plot.
     *
     * <p>Cross-assembly placement cannot physically copy the support block into the destination plot:
     * doing so would itself violate the destination FrameMask. Vanilla BlockItem placement still
     * needs to see that support for survival/orientation checks (wall torches, buttons, etc.), so the
     * destination route installs exactly one virtual state for the duration of the nested placement
     * call. Writes remain fully governed by FrameMaskWriteGuard.</p>
     */
    public static <T> T withCrossFrameVirtualSupport(
            ServerLevel level,
            BlockPos position,
            BlockState state,
            Supplier<T> action) {
        CrossFrameVirtualSupport previous = CROSS_FRAME_VIRTUAL_SUPPORT.get();
        CROSS_FRAME_VIRTUAL_SUPPORT.set(new CrossFrameVirtualSupport(
                level, position.immutable(), state));
        try {
            return withVirtualReads(action);
        } finally {
            if (previous == null) {
                CROSS_FRAME_VIRTUAL_SUPPORT.remove();
            } else {
                CROSS_FRAME_VIRTUAL_SUPPORT.set(previous);
            }
        }
    }

    public static <T> T withVirtualReadsExcludingExternalRails(Supplier<T> action) {
        int previous = EXTERNAL_RAIL_ISOLATION_DEPTH.get();
        EXTERNAL_RAIL_ISOLATION_DEPTH.set(previous + 1);
        try { return withVirtualReads(action); }
        finally {
            if (previous == 0) EXTERNAL_RAIL_ISOLATION_DEPTH.remove();
            else EXTERNAL_RAIL_ISOLATION_DEPTH.set(previous);
        }
    }

    public static void withVirtualReadsExcludingExternalRails(Runnable action) {
        withVirtualReadsExcludingExternalRails(() -> { action.run(); return null; });
    }

    public static @Nullable BlockState virtualBlockState(ServerLevel level, BlockPos globalPlotPosition) {
        if (VIRTUAL_READ_DEPTH.get() <= 0) return null;

        CrossFrameVirtualSupport crossFrameSupport = CROSS_FRAME_VIRTUAL_SUPPORT.get();
        if (crossFrameSupport != null
                && crossFrameSupport.level() == level
                && crossFrameSupport.position().equals(globalPlotPosition)) {
            return crossFrameSupport.state();
        }

        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !isManagedSubLevel(subLevel)) return null;
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) return null;
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        boolean pendingContraption = manager.pendingContraptionMove(ownerId).isPresent();
        if (assembly == null || manager.isContentRecoveryLocked(ownerId)
                || manager.pendingPistonMove(ownerId).isPresent()
                || !pendingContraption
                        && !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) return null;

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) return null;
        if (!level.hasChunkAt(globalPlotPosition)
                || !level.getChunkAt(globalPlotPosition).getBlockState(globalPlotPosition).isAir()) return null;

        BlockPos hostPosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!touchesAssemblyFace(assembly, hostPosition)
                || !MechanismAssemblyHost.samePhysicalHost(level, assembly, hostPosition)) return null;

        // Create extracts the physical parent blocks while the managed mini world must remain
        // structurally coherent. During that journal we answer exclusively from the frozen set of
        // adjacent blocks captured in the same contraption. The snapshot is expressed in immutable
        // logical/source coordinates, so it remains valid while Create continuously translates or
        // yaw-rotates the child pose and the assembly is intentionally no longer docked. Live parent
        // projection still requires a docked boundary outside this journal. An uncaptured neighbour
        // is deliberately projected as air while moving.
        BlockState hostState = SableAssemblyMoveContext.frozenMovedBlockState(level, hostPosition);
        if (hostState == null && pendingContraption) {
            hostState = manager.pendingContraptionBoundaryState(ownerId, hostPosition)
                    .orElse(Blocks.AIR.defaultBlockState());
        } else if (hostState == null) {
            if (!level.hasChunkAt(hostPosition)) return null;
            hostState = level.getChunkAt(hostPosition).getBlockState(hostPosition);
        }
        if (hostState.is(ModRegistries.MECHANISM_FRAME.get())) return null;
        if (hostState.isAir()) {
            return pendingContraption ? Blocks.AIR.defaultBlockState() : null;
        }
        ResourceLocation hostId = BuiltInRegistries.BLOCK.getKey(hostState.getBlock());
        if (hostId != null && AntikytheraMechanism.MOD_ID.equals(hostId.getNamespace())) return null;
        if (EXTERNAL_RAIL_ISOLATION_DEPTH.get() > 0 && hostState.is(BlockTags.RAILS)) return Blocks.AIR.defaultBlockState();
        return hostState;
    }

    public static void parentBlockChanged(ServerLevel level, BlockPos hostPosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> visited = new HashSet<>();
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = hostPosition.relative(directionToFrame);
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null || !visited.add(assembly.id())
                    || manager.pendingContraptionMove(assembly.id()).isPresent()
                    || !MechanismAssemblyHost.samePhysicalHost(level, assembly, hostPosition)) continue;
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) continue;

            Direction physicalBoundary = directionToFrame.getOpposite();
            Direction logicalBoundary = assembly.orientation().toLogical(physicalBoundary);
            BlockState hostState = level.getChunkAt(hostPosition).getBlockState(hostPosition);
            Block sourceBlock = RedstoneBoundaryNeighborContext.sourceOr(hostState.getBlock());
            boolean mirrorSignalUpdateCenter = sourceBlock.defaultBlockState().isSignalSource();
            for (BlockPos local : boundaryCells(assembly, framePosition, logicalBoundary)) {
                BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(subLevel, local);
                if (!level.hasChunkAt(miniGlobal)) continue;
                BlockState current = level.getChunkAt(miniGlobal).getBlockState(miniGlobal);
                if (!current.isAir()) {
                    BlockPos shellGlobal = miniGlobal.relative(logicalBoundary);
                    BlockState updated = withVirtualReads(
                            () -> current.updateShape(logicalBoundary, hostState, level, miniGlobal, shellGlobal));
                    if (!updated.equals(current)) Block.updateOrDestroy(current, updated, level, miniGlobal, Block.UPDATE_ALL);
                    BlockState afterShape = level.getChunkAt(miniGlobal).getBlockState(miniGlobal);
                    if (!afterShape.isAir()) {
                        withVirtualReads(() -> afterShape.handleNeighborChanged(
                                level, miniGlobal, sourceBlock, shellGlobal, false));
                    }
                    subLevel.getPlot().getLightEngine().checkBlock(miniGlobal);
                    subLevel.getPlot().getLightEngine().checkBlock(shellGlobal);
                }

                // Vanilla signal sources emit bounded update centres in addition to the direct
                // neighbour callback. A boundary mini cell may itself be a passive conductor, so
                // notifying only that cell is insufficient: receivers one mini cell farther in (for
                // example dust sitting on a smooth-stone cell powered by a macro lever) never get a
                // chance to recalculate. Mirror the source's update centre at each overlapped boundary
                // mini cell while virtual reads are active. This is also the path used after a
                // topology-deferred source removal, where RedstoneBoundaryNeighborContext preserves
                // the historical source block even though hostState is already air.
                if (mirrorSignalUpdateCenter) {
                    withVirtualReads(() -> level.updateNeighborsAt(miniGlobal, sourceBlock));
                }
            }
        }
    }

    public static void managedBlockChanged(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !isManagedSubLevel(subLevel)) return;
        subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition);
        for (Direction direction : Direction.values()) subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition.relative(direction));
        LazySubLevelLifecycle.requestRetirementCheck(level, MechanismSubLevelService.getOwnerAssemblyId(subLevel));
    }

    private static Set<BlockPos> boundaryCells(MechanismAssembly assembly, BlockPos framePosition, Direction logical) {
        Set<BlockPos> cells = new HashSet<>(4);
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int x = a, y = b, z = 0;
            switch (logical.getAxis()) {
                case X -> { x = logical == Direction.WEST ? 0 : 1; y = a; z = b; }
                case Y -> { x = a; y = logical == Direction.DOWN ? 0 : 1; z = b; }
                case Z -> { x = a; y = b; z = logical == Direction.NORTH ? 0 : 1; }
            }
            cells.add(MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z));
        }
        return cells;
    }

    private static boolean touchesAssemblyFace(MechanismAssembly assembly, BlockPos hostPosition) {
        for (Direction direction : Direction.values()) {
            if (assembly.containsFrame(hostPosition.relative(direction))) return true;
        }
        return false;
    }

    private record CrossFrameVirtualSupport(
            ServerLevel level,
            BlockPos position,
            BlockState state) {
    }
}
