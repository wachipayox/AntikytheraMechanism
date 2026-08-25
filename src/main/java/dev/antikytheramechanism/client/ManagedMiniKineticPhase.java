package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Resolves Create animation phase inputs onto the shared physical half-block lattice. */
public final class ManagedMiniKineticPhase {
    private ManagedMiniKineticPhase() {
    }

    public static @Nullable PhaseContext resolve(Level level, BlockPos childPlotPosition) {
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
        FrameOrientation orientation = frameEntity.getFrameOrientation();
        BlockPos physicalCell = orientation.logicalCellToPhysical(
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        BlockPos frame = owner.position();
        BlockPos physicalMini = new BlockPos(
                frame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getX(),
                frame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getY(),
                frame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getZ());
        return new PhaseContext(physicalMini, orientation);
    }

    public record PhaseContext(BlockPos physicalMiniPosition, FrameOrientation orientation) {
    }
}
