package dev.antikytheramechanism.compat.simulated;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Physics Assembler-specific support projection for a HIDDEN Mechanism Frame.
 *
 * <p>Simulated deliberately accepts any non-empty support shape on the contacted face; unlike
 * vanilla sturdy-face consumers it does not require a full/center/rigid face. A visible Frame gets
 * that tiny surface from its cage bars naturally. When the shell is HIDDEN, reproduce the same
 * semantics from the real mini payload instead of resurrecting Frame collision: one boundary mini
 * whose own support shape reaches the contacted face is sufficient.</p>
 */
public final class PhysicsAssemblerFrameSupport {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;
    private static final String MANAGED_NAME_PREFIX = "antikythera-";

    private PhysicsAssemblerFrameSupport() {
    }

    /** @return null when this is not a resolvable Mechanism Frame support query. */
    public static @Nullable Boolean query(
            LevelReader reader,
            BlockPos framePosition,
            Direction outwardFace) {
        if (!(reader instanceof Level level)
                || !level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        if (level instanceof ServerLevel serverLevel) {
            return queryServer(serverLevel, framePosition, outwardFace);
        }
        if (level.isClientSide) {
            return queryClient(level, framePosition, outwardFace);
        }
        return null;
    }

    private static boolean queryServer(
            ServerLevel level,
            BlockPos framePosition,
            Direction outwardFace) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return false;
        }

        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        if (child == null || child.isRemoved()) {
            return false;
        }
        Direction logicalFace = assembly.orientation().toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }

        for (Cell cell : boundaryCells(outwardFace)) {
            BlockPos mini = MiniCoordinateMapper.physicalFrameCellToMini(
                    assembly, framePosition, cell.x(), cell.y(), cell.z());
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
            if (!level.hasChunkAt(global)) {
                return false;
            }
            BlockState miniState = level.getChunkAt(global).getBlockState(global);
            if (hasAnySupportSurface(level, global, miniState, logicalFace)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Boolean queryClient(
            Level level,
            BlockPos framePosition,
            Direction outwardFace) {
        if (!(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frameEntity)
                || frameEntity.getAssemblyId() == null) {
            return null;
        }

        FrameOrientation orientation = frameEntity.getFrameOrientation();
        Direction logicalFace = orientation.toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        String expectedName = MANAGED_NAME_PREFIX + frameEntity.getAssemblyId();
        SubLevel child = null;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (candidate.isRemoved() || !expectedName.equals(candidate.getName())) {
                continue;
            }
            if (child != null && child != candidate) {
                return null;
            }
            child = candidate;
        }
        if (child == null) {
            return false;
        }

        BlockPos logicalFrameOffset = frameEntity.getLogicalFrameOffset();
        for (Cell physicalCell : boundaryCells(outwardFace)) {
            BlockPos logicalCell = orientation.physicalCellToLogical(
                    physicalCell.x(), physicalCell.y(), physicalCell.z());
            BlockPos mini = new BlockPos(
                    logicalFrameOffset.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + logicalCell.getX(),
                    logicalFrameOffset.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + logicalCell.getY(),
                    logicalFrameOffset.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + logicalCell.getZ());
            BlockPos global = child.getPlot().getCenterBlock().offset(mini);
            if (!level.hasChunkAt(global)) {
                return null;
            }
            BlockState miniState = level.getBlockState(global);
            if (hasAnySupportSurface(level, global, miniState, logicalFace)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnySupportSurface(
            Level level,
            BlockPos position,
            BlockState state,
            Direction face) {
        if (state.isAir()) {
            return false;
        }
        return MiniWorldEnvironment.withVirtualReads(
                () -> !state.getBlockSupportShape(level, position).getFaceShape(face).isEmpty());
    }

    private static Cell[] boundaryCells(Direction face) {
        Cell[] result = new Cell[4];
        int index = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                result[index++] = switch (face.getAxis()) {
                    case X -> new Cell(face == Direction.WEST ? 0 : 1, a, b);
                    case Y -> new Cell(a, face == Direction.DOWN ? 0 : 1, b);
                    case Z -> new Cell(a, b, face == Direction.NORTH ? 0 : 1);
                };
            }
        }
        return result;
    }

    private record Cell(int x, int y, int z) {
    }
}
