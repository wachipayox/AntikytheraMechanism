package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Makes one macro Transmission Box node visible to Create's remote managed-mini kinetic graph.
 * Create remains the authority for source selection, cycle conflicts, stress and network ownership.
 */
public final class CreateTransmissionKineticBridge {
    private static final double ALIGNMENT_EPSILON = 1.0E-5;
    private static final float MICRO_RATIO = 2.0F;

    private CreateTransmissionKineticBridge() {
    }

    public static void appendVirtualNeighbours(KineticBlockEntity source, List<BlockPos> neighbours) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (source instanceof TransmissionBoxBlockEntity box) {
            appendMiniNeighboursForBox(level, box, neighbours);
            return;
        }

        ManagedMiniNode mini = resolveManagedMini(level, source.getBlockPos());
        if (mini == null || !eligible(level, mini.assembly())) {
            return;
        }
        Set<BlockPos> known = new HashSet<>(neighbours);
        BlockPos frame = mini.ownerFrame();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = frame.offset(dx, dy, dz);
                    if (!level.hasChunkAt(candidate)
                            || !(level.getBlockEntity(candidate) instanceof TransmissionBoxBlockEntity box)
                            || !sameHost(level, candidate, mini.assembly())) {
                        continue;
                    }
                    if (boxToMiniFactor(box, mini) != 0 && known.add(candidate)) {
                        neighbours.add(candidate.immutable());
                    }
                }
            }
        }
    }

    /** Applies the box's port sign/ratio after Create has evaluated all of its ordinary rules. */
    public static float adjustRotationModifier(
            KineticBlockEntity from,
            KineticBlockEntity to,
            float vanilla) {
        if (!(from.getLevel() instanceof ServerLevel level) || to.getLevel() != level) {
            return vanilla;
        }

        float macroAdjusted = adjustMacroConnection(from, to, vanilla);
        if (macroAdjusted != vanilla || vanilla != 0) {
            return macroAdjusted;
        }

        if (from instanceof TransmissionBoxBlockEntity box) {
            ManagedMiniNode mini = resolveManagedMini(level, to.getBlockPos());
            return mini == null ? 0 : boxToMiniFactor(box, mini);
        }
        if (to instanceof TransmissionBoxBlockEntity box) {
            ManagedMiniNode mini = resolveManagedMini(level, from.getBlockPos());
            if (mini == null) {
                return 0;
            }
            float forward = boxToMiniFactor(box, mini);
            return forward == 0 ? 0 : 1.0F / forward;
        }
        return vanilla;
    }

    private static float adjustMacroConnection(
            KineticBlockEntity from,
            KineticBlockEntity to,
            float vanilla) {
        if (vanilla == 0 || from.getBlockPos().distManhattan(to.getBlockPos()) != 1) {
            return vanilla;
        }

        float modifier = vanilla;
        if (from instanceof TransmissionBoxBlockEntity box) {
            Direction face = directionFromTo(box.getBlockPos(), to.getBlockPos());
            if (face == null || box.faceMode(face) != TransmissionBoxFaceMode.MACRO) {
                return 0;
            }
            modifier *= box.sideSign(face);
        }
        if (to instanceof TransmissionBoxBlockEntity box) {
            Direction face = directionFromTo(box.getBlockPos(), from.getBlockPos());
            if (face == null || box.faceMode(face) != TransmissionBoxFaceMode.MACRO) {
                return 0;
            }
            modifier /= box.sideSign(face);
        }
        return modifier;
    }

    private static void appendMiniNeighboursForBox(
            ServerLevel level,
            TransmissionBoxBlockEntity box,
            List<BlockPos> neighbours) {
        Set<BlockPos> known = new HashSet<>(neighbours);
        Set<UUID> visitedAssemblies = new HashSet<>();
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        BlockPos boxPos = box.getBlockPos();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos frame = boxPos.offset(dx, dy, dz);
                    MechanismAssembly assembly = manager.getAssemblyAt(frame).orElse(null);
                    if (assembly == null
                            || !eligible(level, assembly)
                            || !sameHost(level, boxPos, assembly)) {
                        continue;
                    }
                    // Multiple nearby Frames may belong to the same assembly, but each physical Frame
                    // has distinct 2x2x2 cells. Do not skip the frame itself; only cache the expensive
                    // sublevel lookup implicitly through findExisting below.
                    ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
                    if (subLevel == null || subLevel.isRemoved()) {
                        continue;
                    }
                    for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                        for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                            for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                                BlockPos miniPos = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
                                BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, miniPos);
                                if (!level.hasChunkAt(global)
                                        || !(level.getBlockEntity(global) instanceof KineticBlockEntity)) {
                                    continue;
                                }
                                ManagedMiniNode mini = resolveManagedMini(level, global);
                                if (mini != null
                                        && boxToMiniFactor(box, mini) != 0
                                        && known.add(global)) {
                                    neighbours.add(global.immutable());
                                }
                            }
                        }
                    }
                    visitedAssemblies.add(assembly.id());
                }
            }
        }
    }

    /** Returns target mini RPM per one canonical macro RPM stored by the box. */
    private static float boxToMiniFactor(TransmissionBoxBlockEntity box, ManagedMiniNode mini) {
        if (!(box.getLevel() instanceof ServerLevel level)
                || !eligible(level, mini.assembly())
                || !sameHost(level, box.getBlockPos(), mini.assembly())) {
            return 0;
        }

        BlockEntity blockEntity = level.getBlockEntity(mini.globalPlotPosition());
        if (!(blockEntity instanceof KineticBlockEntity target)) {
            return 0;
        }
        BlockState targetState = target.getBlockState();
        if (!(targetState.getBlock() instanceof IRotate targetRotate)) {
            return 0;
        }

        Float resolved = null;
        for (Direction face : Direction.values()) {
            if (box.faceMode(face) != TransmissionBoxFaceMode.MICRO
                    || !matchesMicroFace(box.getBlockPos(), mini.physicalMini(), face)
                    || !targetRotate.hasShaftTowards(
                            level,
                            target.getBlockPos(),
                            targetState,
                            face.getOpposite())) {
                continue;
            }
            resolved = mergeFactor(resolved, MICRO_RATIO * box.sideSign(face));
            if (resolved != null && Float.isNaN(resolved)) {
                return 0;
            }
        }

        Direction.Axis axis = box.structuralAxis();
        boolean smallTarget = ICogWheel.isSmallCog(targetState);
        boolean largeTarget = ICogWheel.isLargeCog(targetState);
        if ((smallTarget || largeTarget) && targetRotate.getRotationAxis(targetState) == axis) {
            BlockPos boxMiniBase = new BlockPos(
                    box.getBlockPos().getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS,
                    box.getBlockPos().getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS,
                    box.getBlockPos().getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS);
            for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
                TransmissionBoxCogMode mode = box.cornerMode(corner);
                if (mode == TransmissionBoxCogMode.EMPTY) {
                    continue;
                }
                BlockPos cogCell = boxMiniBase.offset(
                        corner.cell(Direction.Axis.X),
                        corner.cell(Direction.Axis.Y),
                        corner.cell(Direction.Axis.Z));
                BlockPos diff = mini.physicalMini().subtract(cogCell);
                if (axis.choose(diff.getX(), diff.getY(), diff.getZ()) != 0) {
                    continue;
                }
                int first = Math.abs(firstPerpendicular(axis).choose(diff.getX(), diff.getY(), diff.getZ()));
                int second = Math.abs(secondPerpendicular(axis).choose(diff.getX(), diff.getY(), diff.getZ()));

                float factor = 0;
                if (mode == TransmissionBoxCogMode.SMALL) {
                    if (smallTarget && first + second == 1) {
                        factor = -MICRO_RATIO;
                    } else if (largeTarget && first == 1 && second == 1) {
                        factor = -MICRO_RATIO * 0.5F;
                    }
                } else if (mode == TransmissionBoxCogMode.LARGE
                        && smallTarget
                        && first == 1
                        && second == 1) {
                    factor = -MICRO_RATIO * 2.0F;
                }
                if (factor != 0) {
                    resolved = mergeFactor(resolved, factor);
                    if (resolved != null && Float.isNaN(resolved)) {
                        return 0;
                    }
                }
            }
        }
        return resolved == null ? 0 : resolved;
    }

    private static boolean matchesMicroFace(BlockPos box, BlockPos mini, Direction face) {
        int minX = box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minY = box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minZ = box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int expected = switch (face.getAxis()) {
            case X -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minX + 2 : minX - 1;
            case Y -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minY + 2 : minY - 1;
            case Z -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minZ + 2 : minZ - 1;
        };
        int actual = face.getAxis().choose(mini.getX(), mini.getY(), mini.getZ());
        if (actual != expected) {
            return false;
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == face.getAxis()) {
                continue;
            }
            int min = axis.choose(minX, minY, minZ);
            int value = axis.choose(mini.getX(), mini.getY(), mini.getZ());
            if (value < min || value > min + 1) {
                return false;
            }
        }
        return true;
    }

    private static ManagedMiniNode resolveManagedMini(ServerLevel level, BlockPos globalPlotPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }
        UUID assemblyId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (assemblyId == null) {
            return null;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(assemblyId).orElse(null);
        if (assembly == null) {
            return null;
        }
        BlockPos mini = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, mini)) {
            return null;
        }
        BlockPos frame = MiniCoordinateMapper.miniToFrame(assembly, mini);
        BlockPos logicalCell = MiniCoordinateMapper.cellInFrame(mini);
        BlockPos physicalCell = assembly.orientation().logicalCellToPhysical(
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        BlockPos physicalMini = new BlockPos(
                frame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getX(),
                frame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getY(),
                frame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getZ());
        return new ManagedMiniNode(assembly, frame.immutable(), globalPlotPosition.immutable(), physicalMini);
    }

    private static boolean eligible(ServerLevel level, MechanismAssembly assembly) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
        return !manager.isContentRecoveryLocked(assembly.id())
                && manager.pendingPistonMove(assembly.id()).isEmpty()
                && manager.pendingContraptionMove(assembly.id()).isEmpty()
                && host.allowed()
                && MechanismAssemblyHost.boundaryIsAligned(level, assembly, ALIGNMENT_EPSILON);
    }

    private static boolean sameHost(ServerLevel level, BlockPos box, MechanismAssembly assembly) {
        return MechanismAssemblyHost.sameResolvedHost(level, box, assembly.origin());
    }

    private static @org.jetbrains.annotations.Nullable Direction directionFromTo(BlockPos from, BlockPos to) {
        BlockPos diff = to.subtract(from);
        return diff.distManhattan(BlockPos.ZERO) == 1
                ? Direction.getNearest(diff.getX(), diff.getY(), diff.getZ())
                : null;
    }

    private static Float mergeFactor(Float current, float candidate) {
        if (current == null) {
            return candidate;
        }
        return Math.abs(current - candidate) <= 1.0E-5F ? current : Float.NaN;
    }

    private static Direction.Axis firstPerpendicular(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.Y;
            case Y -> Direction.Axis.X;
            case Z -> Direction.Axis.X;
        };
    }

    private static Direction.Axis secondPerpendicular(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.Z;
            case Y -> Direction.Axis.Z;
            case Z -> Direction.Axis.Y;
        };
    }

    private record ManagedMiniNode(
            MechanismAssembly assembly,
            BlockPos ownerFrame,
            BlockPos globalPlotPosition,
            BlockPos physicalMini) {
    }
}
