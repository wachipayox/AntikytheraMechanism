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
 * Read-only projection of directly adjacent parent-world blocks into the mini world's exterior.
 * Nothing is copied into the Sable plot: no duplicate BlockEntities, ticking, drops or rendering.
 *
 * <p>The projection is deliberately scoped. Returning virtual solids from every Level#getBlockState
 * call leaks fake blocks into Sable's lighting/chunk bookkeeping and can make real mini blocks render
 * black. Callers that genuinely evaluate placement/support opt in with {@link #withVirtualReads}.</p>
 */
public final class MiniWorldEnvironment {
    private static final double WORLD_ALIGNED_EPSILON = 1.0E-5;
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
     * Makes ordinary parent-world support visible while hiding parent-world rails from rail topology.
     *
     * <p>A projected solid floor is meaningful support for a mini rail. A projected parent rail is
     * not a safe graph neighbour: vanilla {@code RailState} assumes every discovered rail lives in
     * the same mutable Level coordinate space and can call {@code setBlock} on it while reconnecting
     * the graph. A parent rail represented at a read-only service-shell coordinate violates that
     * assumption and can cause reconnect/remove/drop loops. Rail lifecycle therefore sees external
     * rails as air but continues to see all other adjacent parent blocks normally.</p>
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
                || manager.pendingContraptionMove(ownerId).isPresent()) {
            return null;
        }

        if (!assembly.poseTarget().approximatelyEquals(
                AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
            return null;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
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

        // RailState may mutate every rail it discovers. Parent rails are read-only projections, so
        // exposing them as graph neighbours is unsafe; keep the physical support below visible.
        if (EXTERNAL_RAIL_ISOLATION_DEPTH.get() > 0 && parentState.is(BlockTags.RAILS)) {
            return Blocks.AIR.defaultBlockState();
        }
        return parentState;
    }

    /**
     * Replays the two vanilla neighbour reactions that matter at a virtual boundary: shape/support
     * recomputation and the ordinary neighbourChanged callback. This makes cached state properties
     * (notably NoteBlock.INSTRUMENT) follow the real parent block instead of only sampling it once
     * during placement.
     */
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
                        () -> current.updateShape(boundary, parentState, level, miniGlobal, shellGlobal));
                if (!updated.equals(current)) {
                    Block.updateOrDestroy(current, updated, level, miniGlobal, Block.UPDATE_ALL);
                }

                BlockState afterShape = level.getChunkAt(miniGlobal).getBlockState(miniGlobal);
                if (!afterShape.isAir()) {
                    withVirtualReads(() -> afterShape.handleNeighborChanged(
                            level,
                            miniGlobal,
                            parentState.getBlock(),
                            shellGlobal,
                            false));
                }

                subLevel.getPlot().getLightEngine().checkBlock(miniGlobal);
                subLevel.getPlot().getLightEngine().checkBlock(shellGlobal);
            }
        }
    }

    /** Dirties local plot lighting after a successful managed mini-world mutation. */
    public static void managedBlockChanged(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !isManagedSubLevel(subLevel)) {
            return;
        }
        subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition);
        for (Direction direction : Direction.values()) {
            subLevel.getPlot().getLightEngine().checkBlock(globalPlotPosition.relative(direction));
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
