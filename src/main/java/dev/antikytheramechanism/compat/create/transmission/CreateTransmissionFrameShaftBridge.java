package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.CreateRotationPropagatorAccessor;
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
 * Corrects straight Transmission Box <-> managed-mini shaft links for Frame-local axes.
 *
 * <p>The managed child stores its kinetic BlockState in immutable logical Frame axes, while the
 * Transmission Box lives in the physical host. Physical port directions are mapped back to the
 * child's logical axes before asking Create whether a shaft exists. Create's own axis modifier is
 * then reused so GearboxBlockEntity/SplitShaftBlockEntity retain their native per-face signs.</p>
 */
public final class CreateTransmissionFrameShaftBridge {
    private static final double ALIGNMENT_EPSILON = 1.0E-5;
    private static final float MICRO_RATIO = 2.0F;

    private CreateTransmissionFrameShaftBridge() {
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
                            || straightShaftFactor(box, mini) == 0.0F) {
                        continue;
                    }
                    if (known.add(candidate)) {
                        neighbours.add(candidate.immutable());
                    }
                }
            }
        }
    }

    /**
     * Overrides only a real straight MICRO shaft relation. Cog meshes, Box-to-Box links and ordinary
     * macro connections keep the modifier already resolved by CreateTransmissionKineticBridge.
     */
    public static float adjustRotationModifier(
            KineticBlockEntity from,
            KineticBlockEntity to,
            float current) {
        if (!(from.getLevel() instanceof ServerLevel level) || to.getLevel() != level) {
            return current;
        }
        if (from instanceof TransmissionBoxBlockEntity box) {
            ManagedMiniNode mini = resolveManagedMini(level, to.getBlockPos());
            if (mini == null) {
                return current;
            }
            float factor = straightShaftFactor(box, mini);
            return factor == 0.0F ? current : factor;
        }
        if (to instanceof TransmissionBoxBlockEntity box) {
            ManagedMiniNode mini = resolveManagedMini(level, from.getBlockPos());
            if (mini == null) {
                return current;
            }
            float forward = straightShaftFactor(box, mini);
            return forward == 0.0F ? current : 1.0F / forward;
        }
        return current;
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
                                        && straightShaftFactor(box, mini) != 0.0F
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

    /** Returns target mini RPM per one canonical macro RPM for a straight quarter-shaft only. */
    private static float straightShaftFactor(TransmissionBoxBlockEntity box, ManagedMiniNode mini) {
        if (!(box.getLevel() instanceof ServerLevel level)
                || !eligible(level, mini.assembly())
                || !sameHost(level, box.getBlockPos(), mini.assembly())) {
            return 0.0F;
        }

        BlockEntity blockEntity = level.getBlockEntity(mini.globalPlotPosition());
        if (!(blockEntity instanceof KineticBlockEntity target)) {
            return 0.0F;
        }
        BlockState targetState = target.getBlockState();
        if (!(targetState.getBlock() instanceof IRotate targetRotate)) {
            return 0.0F;
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
                    || !targetRotate.hasShaftTowards(
                            level,
                            target.getBlockPos(),
                            targetState,
                            logicalTowardBox)) {
                continue;
            }

            // Mirror RotationPropagator's connectedByAxis branch instead of assuming that the
            // block's canonical rotation axis is the same as the shaft port axis. Native Gearboxes
            // intentionally violate that assumption: their structural AXIS is perpendicular to
            // every exposed shaft. getAxisModifier() also preserves Gearbox/SplitShaft face signs.
            float targetAxisModifier =
                    CreateRotationPropagatorAccessor.antikytheramechanism$getAxisModifier(
                            target, logicalTowardBox);
            if (Math.abs(targetAxisModifier) <= 1.0E-6F) {
                continue;
            }

            int orientationSign = logicalPositive.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            float factor = MICRO_RATIO
                    * box.sideSign(physicalFace)
                    * orientationSign
                    / targetAxisModifier;
            resolved = mergeFactor(resolved, factor);
            if (resolved != null && Float.isNaN(resolved)) {
                return 0.0F;
            }
        }
        return resolved == null ? 0.0F : resolved;
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

    private static Float mergeFactor(Float current, float candidate) {
        if (current == null) {
            return candidate;
        }
        return Math.abs(current - candidate) <= 1.0E-5F ? current : Float.NaN;
    }

    private record ManagedMiniNode(
            MechanismAssembly assembly,
            BlockPos ownerFrame,
            BlockPos globalPlotPosition,
            BlockPos physicalMini) {
    }
}
