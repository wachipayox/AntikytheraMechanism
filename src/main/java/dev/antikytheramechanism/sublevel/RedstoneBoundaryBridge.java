package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Transfers only redstone signal values across the macro/mini boundary.
 *
 * <p>The ordinary projected shell remains a read-only BlockState view used for support and shape
 * queries. Signal queries need one extra rule: a projected parent block must be evaluated at its
 * real parent-world BlockPos, not at the synthetic shell coordinate in the Sable plot. Otherwise
 * blocks whose power calculation reads their surroundings observe the wrong world.</p>
 *
 * <p>The reverse direction is represented by the Mechanism Frame itself. A frame exposes the
 * strongest signal emitted by the four mini cells touching the queried face. No block is copied,
 * no shell coordinate becomes mutable and no lifecycle/drop path crosses the boundary.</p>
 */
public final class RedstoneBoundaryBridge {
    private static final double WORLD_ALIGNED_EPSILON = 1.0E-5;

    private RedstoneBoundaryBridge() {
    }

    /**
     * Returns the projected parent-world signal for a shell coordinate, or {@code null} when this
     * is not a managed virtual-shell query and vanilla should continue normally.
     */
    public static @Nullable Integer projectedParentSignal(
            ServerLevel level,
            BlockPos globalPlotPosition,
            Direction direction,
            boolean direct) {
        BlockState projectedState = MiniWorldEnvironment.virtualBlockState(level, globalPlotPosition);
        if (projectedState == null) {
            return null;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return null;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return null;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        BlockPos parentPosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!level.hasChunkAt(parentPosition)) {
            return 0;
        }

        return direct
                ? projectedState.getDirectSignal(level, parentPosition, direction)
                : weakSignal(projectedState, level, parentPosition, direction);
    }

    /** Returns the signal a physical Mechanism Frame emits toward the querying macro-world side. */
    public static int frameOutputSignal(
            BlockGetter level,
            BlockPos framePosition,
            Direction queryDirection,
            boolean direct) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || !assembly.poseTarget().approximatelyEquals(
                        AssemblyPose.identityAt(assembly.origin()), WORLD_ALIGNED_EPSILON)) {
            return 0;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(serverLevel, assembly);
        if (subLevel == null) {
            return 0;
        }

        Direction outwardFace = queryDirection.getOpposite();
        int strongest = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos local = boundaryCell(assembly, framePosition, outwardFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, local);
                if (!serverLevel.hasChunkAt(global)) {
                    continue;
                }
                BlockState miniState = serverLevel.getChunkAt(global).getBlockState(global);
                if (miniState.isAir()) {
                    continue;
                }

                int signal = MiniWorldEnvironment.withVirtualReads(() -> direct
                        ? miniState.getDirectSignal(serverLevel, global, queryDirection)
                        : weakSignal(miniState, serverLevel, global, queryDirection));
                strongest = Math.max(strongest, signal);
                if (strongest >= 15) {
                    return 15;
                }
            }
        }
        return strongest;
    }

    /**
     * A mini state write can change the signal exposed by its owning frame. Notify the parent-world
     * neighbours so wire, pistons, lamps and modded consumers recalculate against the frame output.
     */
    public static void notifyParentForManagedWrite(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel) || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return;
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
            return;
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        if (!level.hasChunkAt(framePosition)
                || !level.getChunkAt(framePosition).getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }
        level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
    }

    private static int weakSignal(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            Direction direction) {
        int signal = state.getSignal(level, position, direction);
        return state.shouldCheckWeakPower(level, position, direction)
                ? Math.max(signal, level.getDirectSignalTo(position))
                : signal;
    }

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePosition,
            Direction boundary,
            int a,
            int b) {
        int x;
        int y;
        int z;
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
            default -> throw new IllegalStateException("Unexpected axis " + boundary.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
    }
}
