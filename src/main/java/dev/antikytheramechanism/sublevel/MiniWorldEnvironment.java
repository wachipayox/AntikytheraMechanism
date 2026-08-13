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
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Read-only projection of directly adjacent host-world blocks into the mini world's exterior.
 * Nothing is copied into the Sable plot: no duplicate BlockEntities, ticking, drops or rendering.
 *
 * <p>The host can be the root level or a foreign Sable SubLevel such as a ship. The projection is
 * deliberately scoped. Returning virtual solids from every Level#getBlockState call leaks fake
 * blocks into Sable's lighting/chunk bookkeeping and can make real mini blocks render black. Callers
 * that genuinely evaluate placement/support opt in with {@link #withVirtualReads}.</p>
 */
public final class MiniWorldEnvironment {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;
    private static final String MANAGED_NAME_PREFIX = "antikythera-";
    private static final ThreadLocal<Integer> VIRTUAL_READ_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> EXTERNAL_RAIL_ISOLATION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MiniWorldEnvironment() {
    }

    public static boolean isManagedSubLevel(@Nullable SubLevel subLevel) {
        return subLevel != null
                && subLevel.getName() != null
                && subLevel.getName().startsWith(MANAGED_NAME_PREFIX);
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
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                VIRTUAL_READ_DEPTH.remove();
            } else {
                VIRTUAL_READ_DEPTH.set(previous);
            }
        }
    }

    public static void withVirtualReads(Runnable action) {
        withVirtualReads(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Makes ordinary host support visible while hiding host rails from rail topology.
     *
     * <p>A projected solid floor is meaningful support for a mini rail. A projected host rail is
     * not a safe graph neighbour: vanilla RailState assumes every discovered rail lives in the same
     * mutable Level coordinate space and can call setBlock on it while reconnecting the graph. A host
     * rail represented at a read-only service-shell coordinate violates that assumption.</p>
     */
    public static <T> T withVirtualReadsExcludingExternalRails(Supplier<T> action) {
        int previous = EXTERNAL_RAIL_ISOLATION_DEPTH.get();
        EXTERNAL_RAIL_ISOLATION_DEPTH.set(previous + 1);
        try {
            return withVirtualReads(action);
        } finally {
            if (previous == 0) {
                EXTERNAL_RAIL_ISOLATION_DEPTH.remove();
            } else {
                EXTERNAL_RAIL_ISOLATION_DEPTH.set(previous);
            }
        }
    }

    public static void withVirtualReadsExcludingExternalRails(Runnable action) {
        withVirtualReadsExcludingExternalRails(() -> {
            action.run();
            return null;
        });
    }

    public static @Nullable BlockState virtualBlockState(ServerLevel level, BlockPos globalPlotPosition) {
        if (VIRTUAL_READ_DEPTH.get() <= 0) {
            return null;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !isManagedSubLevel(subLevel)) {
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
                || manager.pendingContraptionMove(ownerId).isPresent()
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return null;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
            return null;
        }

        // Physical child-plot content, including service-shell ports, always wins over virtual context.
        if (!level.hasChunkAt(globalPlotPosition)
                || !level.getChunkAt(globalPlotPosition).getBlockState(globalPlotPosition).isAir()) {
            return null;
        }

        BlockPos hostPosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!touchesAssemblyFace(assembly, hostPosition)
                || !MechanismAssemblyHost.samePhysicalHost(level, assembly, hostPosition)
                || !level.hasChunkAt(hostPosition)) {
            return null;
        }

        BlockState hostState = level.getChunkAt(hostPosition).getBlockState(hostPosition);
        if (hostState.isAir() || hostState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        ResourceLocation hostId = BuiltInRegistries.BLOCK.getKey(hostState.getBlock());
        if (hostId != null && AntikytheraMechanism.MOD_ID.equals(hostId.getNamespace())) {
            return null;
        }

        // RailState may mutate every rail it discovers. Host rails are read-only projections, so
        // exposing them as graph neighbours is unsafe; keep the physical support below visible.
        if (EXTERNAL_RAIL_ISOLATION_DEPTH.get() > 0 && hostState.is(BlockTags.RAILS)) {
            return Blocks.AIR.defaultBlockState();
        }
        return hostState;
    }

    /**
     * Replays the two vanilla neighbour reactions that matter at a virtual boundary: shape/support
     * recomputation and the ordinary neighbourChanged callback. The changed block may live in root or
     * in the same foreign Sable host as the Frame; unrelated SubLevels never cross the boundary.
     */
    public static void parentBlockChanged(ServerLevel level, BlockPos hostPosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Set<UUID> visited = new HashSet<>();
        for (Direction directionToFrame : Direction.values()) {
            BlockPos framePosition = hostPosition.relative(directionToFrame);
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null
                    || !visited.add(assembly.id())
                    || !MechanismAssemblyHost.samePhysicalHost(level, assembly, hostPosition)) {
                continue;
            }
            ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
            if (subLevel == null
                    || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
                continue;
            }

            Direction boundary = directionToFrame.getOpposite();
            BlockState hostState = level.getChunkAt(hostPosition).getBlockState(hostPosition);
            for (BlockPos local : boundaryCells(assembly, framePosition, boundary)) {
                BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(subLevel, local);
                if (!level.hasChunkAt(miniGlobal)) {
                    continue;
                }
                BlockState current = level.getChunkAt(miniGlobal).getBlockState(miniGlobal);
                if (current.isAir()) {
                    continue;
                }

                BlockPos shellGlobal = miniGlobal.relative(boundary);
                BlockState updated = withVirtualReads(
                        () -> current.updateShape(boundary, hostState, level, miniGlobal, shellGlobal));
                if (!updated.equals(current)) {
                    Block.updateOrDestroy(current, updated, level, miniGlobal, Block.UPDATE_ALL);
                }

                BlockState afterShape = level.getChunkAt(miniGlobal).getBlockState(miniGlobal);
                if (!afterShape.isAir()) {
                    withVirtualReads(() -> afterShape.handleNeighborChanged(
                            level,
                            miniGlobal,
                            hostState.getBlock(),
                            shellGlobal,
                            false));
                }

                subLevel.getPlot().getLightEngine().checkBlock(miniGlobal);
                subLevel.getPlot().getLightEngine().checkBlock(shellGlobal);
            }
        }
    }

    /** Dirties local plot lighting and schedules a deferred empty-content check after a managed write. */
    public static void managedBlockChanged(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !isManagedSubLevel(subLevel)) {
            return;
        }
        subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition);
        for (Direction direction : Direction.values()) {
            subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition.relative(direction));
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        LazySubLevelLifecycle.requestRetirementCheck(level, ownerId);
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

    private static boolean touchesAssemblyFace(MechanismAssembly assembly, BlockPos hostPosition) {
        for (Direction direction : Direction.values()) {
            if (assembly.containsFrame(hostPosition.relative(direction))) {
                return true;
            }
        }
        return false;
    }
}
