package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Projects a complete 2x2 mini boundary face into vanilla's macro support query.
 *
 * <p>A Mechanism Frame face is sturdy only when all four mini cells on that exterior face are
 * occupied and each real mini BlockState reports that same outward face as sturdy for the exact
 * {@link SupportType} vanilla is asking about. The Frame cage itself never manufactures support.</p>
 */
public final class FrameFaceSupport {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;

    private FrameFaceSupport() {
    }

    /**
     * @return null outside the authoritative server path so ordinary BlockState handling can continue.
     */
    public static @Nullable Boolean query(
            BlockGetter level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        BlockState frameState = serverLevel.getBlockState(framePosition);
        if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || assembly.containsFrame(framePosition.relative(outwardFace))
                || !MechanismAssemblyHost.boundaryIsAligned(
                        serverLevel, assembly, HOST_ALIGNMENT_EPSILON)) {
            return false;
        }

        if (!(serverLevel.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frameEntity)
                || (frameEntity.getOccupiedMask() & requiredFaceMask(outwardFace))
                        != requiredFaceMask(outwardFace)) {
            return false;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(serverLevel, assembly);
        if (subLevel == null) {
            return false;
        }

        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos mini = boundaryCell(assembly, framePosition, outwardFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                if (!serverLevel.hasChunkAt(global)) {
                    return false;
                }

                BlockState miniState = serverLevel.getChunkAt(global).getBlockState(global);
                if (miniState.isAir()) {
                    return false;
                }

                boolean sturdy = MiniWorldEnvironment.withVirtualReads(
                        () -> miniState.isFaceSturdy(
                                serverLevel, global, outwardFace, supportType));
                if (!sturdy) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int requiredFaceMask(Direction face) {
        int mask = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                int x;
                int y;
                int z;
                switch (face.getAxis()) {
                    case X -> {
                        x = face == Direction.WEST ? 0 : 1;
                        y = a;
                        z = b;
                    }
                    case Y -> {
                        x = a;
                        y = face == Direction.DOWN ? 0 : 1;
                        z = b;
                    }
                    case Z -> {
                        x = a;
                        y = b;
                        z = face == Direction.NORTH ? 0 : 1;
                    }
                    default -> throw new IllegalStateException("Unexpected axis " + face.getAxis());
                }
                mask |= 1 << MiniCoordinateMapper.cellIndex(x, y, z);
            }
        }
        return mask;
    }

    private static BlockPos boundaryCell(
            MechanismAssembly assembly,
            BlockPos framePosition,
            Direction face,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (face.getAxis()) {
            case X -> {
                x = face == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = face == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = face == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + face.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
    }
}
