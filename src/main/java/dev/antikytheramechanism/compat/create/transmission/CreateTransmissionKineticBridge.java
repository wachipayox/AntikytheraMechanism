package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateKineticConnectionMath;
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
 * Makes the macro Transmission Box node visible to managed-mini Create networks and to neighbouring
 * Transmission Boxes through their physical half-scale ports. Create remains the authority for source
 * selection, cycle conflicts, stress and network ownership.
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
            appendTransmissionBoxNeighbours(level, box, neighbours);
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

    /** Applies the box's port sign/ratio after Create and the cross-Frame physical bridge have run. */
    public static float adjustRotationModifier(
            KineticBlockEntity from,
            KineticBlockEntity to,
            float vanilla) {
        if (!(from.getLevel() instanceof ServerLevel level) || to.getLevel() != level) {
            return vanilla;
        }

        if (from instanceof TransmissionBoxBlockEntity fromBox
                && to instanceof TransmissionBoxBlockEntity toBox) {
            float microConnection = boxToBoxFactor(level, fromBox, toBox);
            if (microConnection != 0) {
                return microConnection;
            }
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

    /** Adds only non-vanilla box neighbours, including every native cog mesh on the half-scale lattice. */
    private static void appendTransmissionBoxNeighbours(
            ServerLevel level,
            TransmissionBoxBlockEntity box,
            List<BlockPos> neighbours) {
        Set<BlockPos> known = new HashSet<>(neighbours);
        BlockPos origin = box.getBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidatePos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(candidatePos)
                            || !(level.getBlockEntity(candidatePos) instanceof TransmissionBoxBlockEntity candidate)
                            || !MechanismAssemblyHost.sameResolvedHost(level, origin, candidatePos)
                            || boxToBoxFactor(level, box, candidate) == 0) {
                        continue;
                    }
                    if (known.add(candidatePos)) {
                        neighbours.add(candidatePos.immutable());
                    }
                }
            }
        }
    }

    /**
     * Canonical target-box RPM per one canonical source-box RPM. The common macro->micro factor
     * cancels between boxes, so corner cogs use Create's native cog modifier directly.
     */
    private static float boxToBoxFactor(
            ServerLevel level,
            TransmissionBoxBlockEntity from,
            TransmissionBoxBlockEntity to) {
        if (from == to
                || !MechanismAssemblyHost.sameResolvedHost(level, from.getBlockPos(), to.getBlockPos())) {
            return 0;
        }

        Float resolved = null;
        Direction directFace = directionFromTo(from.getBlockPos(), to.getBlockPos());
        if (directFace != null
                && from.faceMode(directFace) == TransmissionBoxFaceMode.MICRO
                && to.faceMode(directFace.getOpposite()) == TransmissionBoxFaceMode.MICRO
                && hasClearStraightMicroPair(from, to, directFace)) {
            float factor = from.sideSign(directFace)
                    / (float) to.sideSign(directFace.getOpposite());
            resolved = mergeFactor(resolved, factor);
        }

        Direction.Axis fromAxis = from.structuralAxis();
        Direction.Axis toAxis = to.structuralAxis();
        for (TransmissionBoxCorner fromCorner : TransmissionBoxCorner.values()) {
            TransmissionBoxCogMode fromMode = from.cornerMode(fromCorner);
            CreateKineticConnectionMath.CogKind fromKind = cogKind(fromMode);
            if (fromKind == CreateKineticConnectionMath.CogKind.NONE) {
                continue;
            }
            BlockPos fromCell = cogCell(from.getBlockPos(), fromCorner);
            for (TransmissionBoxCorner toCorner : TransmissionBoxCorner.values()) {
                TransmissionBoxCogMode toMode = to.cornerMode(toCorner);
                CreateKineticConnectionMath.CogKind toKind = cogKind(toMode);
                if (toKind == CreateKineticConnectionMath.CogKind.NONE) {
                    continue;
                }
                BlockPos diff = cogCell(to.getBlockPos(), toCorner).subtract(fromCell);
                float factor = CreateKineticConnectionMath.cogModifier(
                        fromKind, fromAxis, toKind, toAxis, diff);
                if (factor == 0.0F) {
                    continue;
                }
                resolved = mergeFactor(resolved, factor);
                if (resolved != null && Float.isNaN(resolved)) {
                    return 0;
                }
            }
        }

        return resolved == null || Float.isNaN(resolved) ? 0 : resolved;
    }

    /** At least one of the four aligned half-scale shafts must remain unobstructed by corner cogs. */
    private static boolean hasClearStraightMicroPair(
            TransmissionBoxBlockEntity from,
            TransmissionBoxBlockEntity to,
            Direction face) {
        for (TransmissionBoxCorner fromCorner : TransmissionBoxCorner.values()) {
            if (fromCorner.sign(face.getAxis()) != directionSign(face)) {
                continue;
            }
            TransmissionBoxCorner toCorner = flipCornerAxis(fromCorner, face.getAxis());
            if (from.cornerMode(fromCorner) == TransmissionBoxCogMode.EMPTY
                    && to.cornerMode(toCorner) == TransmissionBoxCogMode.EMPTY) {
                return true;
            }
        }
        return false;
    }

    private static TransmissionBoxCorner flipCornerAxis(
            TransmissionBoxCorner corner,
            Direction.Axis axis) {
        int x = corner.sign(Direction.Axis.X);
        int y = corner.sign(Direction.Axis.Y);
        int z = corner.sign(Direction.Axis.Z);
        return TransmissionBoxCorner.fromSigns(
                axis == Direction.Axis.X ? -x : x,
                axis == Direction.Axis.Y ? -y : y,
                axis == Direction.Axis.Z ? -z : z);
    }

    private static BlockPos cogCell(BlockPos box, TransmissionBoxCorner corner) {
        return new BlockPos(
                box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.X),
                box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.Y),
                box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.Z));
    }

    private static void appendMiniNeighboursForBox(
            ServerLevel level,
            TransmissionBoxBlockEntity box,
            List<BlockPos> neighbours) {
        Set<BlockPos> known = new HashSet<>(neighbours);
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
        for (Direction physicalFace : Direction.values()) {
            if (box.faceMode(physicalFace) != TransmissionBoxFaceMode.MICRO
                    || !matchesMicroFace(box.getBlockPos(), mini.physicalMini(), physicalFace)) {
                continue;
            }
            TransmissionBoxCorner portCorner = microPortCorner(
                    box.getBlockPos(), mini.physicalMini(), physicalFace);
            if (box.cornerMode(portCorner) != TransmissionBoxCogMode.EMPTY) {
                continue;
            }

            Direction logicalTowardBox = mini.assembly().orientation().toLogical(physicalFace.getOpposite());
            Direction physicalPositive = Direction.fromAxisAndDirection(
                    physicalFace.getAxis(), Direction.AxisDirection.POSITIVE);
            Direction logicalPositive = mini.assembly().orientation().toLogical(physicalPositive);
            if (logicalTowardBox == null
                    || logicalPositive == null
                    || targetRotate.getRotationAxis(targetState) != logicalPositive.getAxis()
                    || !targetRotate.hasShaftTowards(
                            level,
                            target.getBlockPos(),
                            targetState,
                            logicalTowardBox)) {
                continue;
            }
            int orientationSign = logicalPositive.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            resolved = mergeFactor(
                    resolved,
                    MICRO_RATIO * box.sideSign(physicalFace) * orientationSign);
            if (resolved != null && Float.isNaN(resolved)) {
                return 0;
            }
        }

        AxisMapping targetPhysicalAxis = physicalRotationAxis(mini.assembly(), targetRotate, targetState);
        CreateKineticConnectionMath.CogKind targetKind = CreateKineticConnectionMath.cogKind(targetState);
        if (targetPhysicalAxis != null && targetKind != CreateKineticConnectionMath.CogKind.NONE) {
            Direction.Axis boxAxis = box.structuralAxis();
            for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
                CreateKineticConnectionMath.CogKind boxKind = cogKind(box.cornerMode(corner));
                if (boxKind == CreateKineticConnectionMath.CogKind.NONE) {
                    continue;
                }
                BlockPos diff = mini.physicalMini().subtract(cogCell(box.getBlockPos(), corner));
                float microModifier = CreateKineticConnectionMath.cogModifier(
                        boxKind, boxAxis, targetKind, targetPhysicalAxis.axis(), diff);
                if (microModifier == 0.0F) {
                    continue;
                }
                resolved = mergeFactor(
                        resolved,
                        MICRO_RATIO * microModifier * targetPhysicalAxis.sign());
                if (resolved != null && Float.isNaN(resolved)) {
                    return 0;
                }
            }
        }
        return resolved == null ? 0 : resolved;
    }

    private static AxisMapping physicalRotationAxis(
            MechanismAssembly assembly,
            IRotate rotate,
            BlockState state) {
        Direction logicalPositive = Direction.fromAxisAndDirection(
                rotate.getRotationAxis(state), Direction.AxisDirection.POSITIVE);
        Direction physicalPositive = assembly.orientation().toPhysical(logicalPositive);
        if (physicalPositive == null) {
            return null;
        }
        return new AxisMapping(
                physicalPositive.getAxis(),
                physicalPositive.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
    }

    private static CreateKineticConnectionMath.CogKind cogKind(TransmissionBoxCogMode mode) {
        return switch (mode) {
            case EMPTY -> CreateKineticConnectionMath.CogKind.NONE;
            case SMALL -> CreateKineticConnectionMath.CogKind.SMALL;
            case LARGE -> CreateKineticConnectionMath.CogKind.LARGE;
        };
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

    private static TransmissionBoxCorner microPortCorner(BlockPos box, BlockPos mini, Direction face) {
        int minX = box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minY = box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minZ = box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int x = face.getAxis() == Direction.Axis.X
                ? directionSign(face)
                : mini.getX() == minX ? -1 : 1;
        int y = face.getAxis() == Direction.Axis.Y
                ? directionSign(face)
                : mini.getY() == minY ? -1 : 1;
        int z = face.getAxis() == Direction.Axis.Z
                ? directionSign(face)
                : mini.getZ() == minZ ? -1 : 1;
        return TransmissionBoxCorner.fromSigns(x, y, z);
    }

    private static int directionSign(Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
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

    private record AxisMapping(Direction.Axis axis, int sign) {
    }

    private record ManagedMiniNode(
            MechanismAssembly assembly,
            BlockPos ownerFrame,
            BlockPos globalPlotPosition,
            BlockPos physicalMini) {
    }
}
