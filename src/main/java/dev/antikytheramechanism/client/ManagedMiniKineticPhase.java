package dev.antikytheramechanism.client;

import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Maps a managed child's private plot coordinate onto the shared half-block lattice of its physical
 * host. Create's kinetic animation phase depends on BlockPos parity; using unrelated plot-yard
 * coordinates makes directly meshed shafts/cogs from different Frame assemblies visibly jump by
 * 22.5 degrees even though their server-side speeds are coherent.
 */
public final class ManagedMiniKineticPhase {
    private ManagedMiniKineticPhase() {
    }

    public static @Nullable BlockPos physicalMiniPosition(Level level, BlockPos childPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, childPlotPosition);
        if (!(containing instanceof ClientSubLevel child)
                || !ManagedClientSubLevelIdentity.isManaged(child)) {
            return null;
        }

        ManagedClientFrameHost.OwningFrame owner =
                ManagedClientFrameHost.resolveOwningFrame(child, childPlotPosition);
        if (owner == null
                || !(level.getBlockEntity(owner.position()) instanceof MechanismFrameBlockEntity frameEntity)) {
            return null;
        }

        BlockPos mini = childPlotPosition.subtract(child.getPlot().getCenterBlock());
        BlockPos logicalCell = MiniCoordinateMapper.cellInFrame(mini);
        BlockPos physicalCell = frameEntity.getFrameOrientation().logicalCellToPhysical(
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        BlockPos frame = owner.position();
        return new BlockPos(
                frame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getX(),
                frame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getY(),
                frame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getZ());
    }
}
