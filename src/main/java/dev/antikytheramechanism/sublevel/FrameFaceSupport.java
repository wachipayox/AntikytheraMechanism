package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Set;
import java.util.UUID;

/**
 * Projects a complete 2x2 mini boundary face into vanilla's macro support query.
 *
 * <p>A Mechanism Frame face is sturdy only when all four real mini cells on that exterior face are
 * occupied and each mini BlockState reports the corresponding logical outward face as sturdy for the
 * exact {@link SupportType} vanilla is asking about. The Frame cage itself never manufactures support.</p>
 *
 * <p>The server reads the physical mini states directly and never treats the synchronized
 * {@link MechanismFrameBlockEntity} occupancy mask as authoritative. That mask is a rendering/client
 * snapshot and may briefly lag a just-completed mini write. The client resolves the corresponding
 * managed Sable child and performs the same four-state query so vanilla placement prediction chooses
 * the same wall/floor variant as the server instead of showing a one-frame correction.</p>
 */
public final class FrameFaceSupport {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;
    private static final double CLIENT_LATTICE_EPSILON = 1.0E-3;
    private static final double SCALE_EPSILON = 1.0E-5;
    private static final String MANAGED_NAME_PREFIX = "antikythera-";

    private FrameFaceSupport() {
    }

    /** @return null when Antikythera cannot authoritatively answer this support query. */
    public static @Nullable Boolean query(
            BlockGetter level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType) {
        if (!(level instanceof Level actualLevel)) {
            return null;
        }

        BlockState frameState = actualLevel.getBlockState(framePosition);
        if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }

        if (actualLevel instanceof ServerLevel serverLevel) {
            return queryServer(serverLevel, framePosition, outwardFace, supportType);
        }
        if (actualLevel.isClientSide) {
            return queryClient(actualLevel, framePosition, outwardFace, supportType);
        }
        return null;
    }

    private static boolean queryServer(
            ServerLevel level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        ServerSupportView view = resolveServerSupportView(manager, level, framePosition, outwardFace);
        if (view == null) {
            return false;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, view.assembly());
        if (subLevel == null) {
            return false;
        }

        // The managed plot never rotates its BlockStates when the physical Frame orientation changes.
        // Translate the queried physical face back into immutable logical mini axes before selecting
        // boundary cells or asking those real BlockStates whether their corresponding face is sturdy.
        Direction logicalFace = view.orientation().toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }

        // Do not gate this query on MechanismFrameBlockEntity#occupiedMask. The mask is synchronized
        // after mini writes and can therefore be one update behind the real plot for a single use.
        // The four physical states below are the actual support authority.
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos mini = boundaryCell(view.logicalFrameOffset(), logicalFace, a, b);
                BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                if (!level.hasChunkAt(global)) {
                    return false;
                }

                BlockState miniState = level.getChunkAt(global).getBlockState(global);
                if (miniState.isAir()) {
                    return false;
                }

                boolean sturdy = MiniWorldEnvironment.withVirtualReads(
                        () -> miniState.isFaceSturdy(level, global, logicalFace, supportType));
                if (!sturdy) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Resolves either the committed Frame mapping or the narrow, synchronous target mapping exposed
     * while Create is placing destination blocks before the durable journal commits frameIndex.
     */
    private static @Nullable ServerSupportView resolveServerSupportView(
            MechanismAssemblyManager manager,
            ServerLevel level,
            BlockPos framePosition,
            Direction outwardFace) {
        CreateAssemblyPlacementContext.Target target =
                CreateAssemblyPlacementContext.targetAt(level, framePosition);
        if (target != null) {
            MechanismAssembly assembly = manager.getAssembly(target.assemblyId()).orElse(null);
            PendingContraptionMove move = manager.pendingContraptionMove(target.assemblyId()).orElse(null);
            FrameOrientation journalOrientation = move != null && move.hasPlacement()
                    ? FrameOrientation.fromQuaternion(move.finalPose().orientation(new Quaterniond())).orElse(null)
                    : null;
            BlockPos expectedLogicalOffset = journalOrientation != null
                    ? journalOrientation.toLogical(framePosition.subtract(move.targetOrigin()))
                    : null;
            if (assembly == null
                    || manager.isContentRecoveryLocked(target.assemblyId())
                    || manager.pendingPistonMove(target.assemblyId()).isPresent()
                    || move == null
                    || !move.hasPlacement()
                    || journalOrientation == null
                    || !journalOrientation.equals(target.orientation())
                    || !move.targetFrames().equals(target.targetFrames())
                    || !move.targetFrames().contains(framePosition)
                    || !target.logicalFrameOffset().equals(expectedLogicalOffset)
                    || move.targetFrames().contains(framePosition.relative(outwardFace))
                    || !pendingTargetIsDocked(move, journalOrientation)
                    || !MechanismAssemblyHost.sameResolvedHost(level, move.targetOrigin(), framePosition)) {
                return null;
            }
            return new ServerSupportView(
                    assembly,
                    journalOrientation,
                    target.logicalFrameOffset(),
                    target.targetFrames());
        }

        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null
                || manager.isContentRecoveryLocked(assembly.id())
                || manager.pendingPistonMove(assembly.id()).isPresent()
                || manager.pendingContraptionMove(assembly.id()).isPresent()
                || assembly.containsFrame(framePosition.relative(outwardFace))
                || !MechanismAssemblyHost.boundaryIsAligned(level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return null;
        }
        return new ServerSupportView(
                assembly,
                assembly.orientation(),
                assembly.logicalFrameOffset(framePosition),
                assembly.frames());
    }

    private static boolean pendingTargetIsDocked(PendingContraptionMove move, FrameOrientation orientation) {
        BlockPos origin = move.targetOrigin();
        if (Math.abs(move.finalPose().anchorX() - (origin.getX() + .5)) > HOST_ALIGNMENT_EPSILON
                || Math.abs(move.finalPose().anchorY() - (origin.getY() + .5)) > HOST_ALIGNMENT_EPSILON
                || Math.abs(move.finalPose().anchorZ() - (origin.getZ() + .5)) > HOST_ALIGNMENT_EPSILON) {
            return false;
        }
        Quaterniond expected = orientation.quaternion(new Quaterniond());
        double dot = move.finalPose().quaternionX() * expected.x
                + move.finalPose().quaternionY() * expected.y
                + move.finalPose().quaternionZ() * expected.z
                + move.finalPose().quaternionW() * expected.w;
        return Math.abs(Math.abs(dot) - 1.0) <= HOST_ALIGNMENT_EPSILON;
    }

    private static @Nullable Boolean queryClient(
            Level level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType) {
        if (!(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frameEntity)) {
            return null;
        }
        UUID assemblyId = frameEntity.getAssemblyId();
        if (assemblyId == null) {
            return null;
        }
        Direction logicalFace = frameEntity.getFrameOrientation().toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }

        // A sibling Frame is continuous mini space, never an exterior support face. This check stays
        // in physical axes because framePosition itself belongs to the physical host.
        if (level.getBlockState(framePosition.relative(outwardFace))
                .is(ModRegistries.MECHANISM_FRAME.get())) {
            return false;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        String expectedName = MANAGED_NAME_PREFIX + assemblyId;
        SubLevel child = null;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (candidate.isRemoved() || !expectedName.equals(candidate.getName())) {
                continue;
            }
            if (child != null && child != candidate) {
                // Ambiguous client state: do not invent support until Sable finishes reconciling it.
                return null;
            }
            child = candidate;
        }
        if (child == null || !hasManagedScale(child.logicalPose())) {
            return null;
        }

        SubLevel host = Sable.HELPER.getContaining(level, framePosition);
        if (host == child || (host != null && isManaged(host))) {
            return false;
        }
        if (host != null && !hasUnitScale(host.logicalPose())) {
            return false;
        }
        if (!orientationsMatch(host, child)) {
            return false;
        }

        Vector3d worldFrameCenter = new Vector3d(
                framePosition.getX() + 0.5,
                framePosition.getY() + 0.5,
                framePosition.getZ() + 0.5);
        if (host != null) {
            host.logicalPose().transformPosition(worldFrameCenter);
        }

        Vector3d childFrameCenter = inverseTransformPosition(child.logicalPose(), worldFrameCenter);
        int centerX = nearestLatticeCoordinate(childFrameCenter.x);
        int centerY = nearestLatticeCoordinate(childFrameCenter.y);
        int centerZ = nearestLatticeCoordinate(childFrameCenter.z);
        if (centerX == Integer.MIN_VALUE
                || centerY == Integer.MIN_VALUE
                || centerZ == Integer.MIN_VALUE) {
            return false;
        }

        // A 2x2x2 Frame is centered on an integer mini-grid vertex: its cells are center-1/center on
        // each logical axis. This remains true for root Frames and for Frames hosted inside a unit-scale
        // Sable body because both host and managed child are transformed through world space above.
        int baseX = centerX - 1;
        int baseY = centerY - 1;
        int baseZ = centerZ - 1;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos global = clientBoundaryCell(baseX, baseY, baseZ, logicalFace, a, b);
                if (!level.hasChunkAt(global)) {
                    return null;
                }
                BlockState miniState = level.getBlockState(global);
                if (miniState.isAir()
                        || !miniState.isFaceSturdy(level, global, logicalFace, supportType)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Vector3d inverseTransformPosition(Pose3dc pose, Vector3dc worldPosition) {
        Vector3d local = new Vector3d(worldPosition).sub(pose.position());
        Quaterniond inverseOrientation = new Quaterniond(pose.orientation()).conjugate().normalize();
        inverseOrientation.transform(local);
        Vector3dc scale = pose.scale();
        local.div(scale.x(), scale.y(), scale.z());
        return local.add(pose.rotationPoint());
    }

    private static int nearestLatticeCoordinate(double coordinate) {
        double rounded = Math.rint(coordinate);
        if (!Double.isFinite(coordinate)
                || Math.abs(coordinate - rounded) > CLIENT_LATTICE_EPSILON
                || rounded < Integer.MIN_VALUE + 1.0
                || rounded > Integer.MAX_VALUE - 1.0) {
            return Integer.MIN_VALUE;
        }
        return (int) rounded;
    }

    private static boolean orientationsMatch(@Nullable SubLevel host, SubLevel child) {
        Quaterniondc expected = host == null
                ? new Quaterniond()
                : host.logicalPose().orientation();
        Quaterniondc actual = child.logicalPose().orientation();
        double dot = expected.x() * actual.x()
                + expected.y() * actual.y()
                + expected.z() * actual.z()
                + expected.w() * actual.w();
        return Math.abs(Math.abs(dot) - 1.0) <= HOST_ALIGNMENT_EPSILON;
    }

    private static boolean hasManagedScale(Pose3dc pose) {
        Vector3dc scale = pose.scale();
        return Math.abs(scale.x() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON
                && Math.abs(scale.y() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON
                && Math.abs(scale.z() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON;
    }

    private static boolean hasUnitScale(Pose3dc pose) {
        Vector3dc scale = pose.scale();
        return Math.abs(scale.x() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.y() - 1.0) <= SCALE_EPSILON
                && Math.abs(scale.z() - 1.0) <= SCALE_EPSILON;
    }

    private static boolean isManaged(SubLevel subLevel) {
        String name = subLevel.getName();
        return name != null && name.startsWith(MANAGED_NAME_PREFIX);
    }

    private static BlockPos clientBoundaryCell(
            int baseX,
            int baseY,
            int baseZ,
            Direction face,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (face.getAxis()) {
            case X -> {
                x = baseX + (face == Direction.WEST ? 0 : 1);
                y = baseY + a;
                z = baseZ + b;
            }
            case Y -> {
                x = baseX + a;
                y = baseY + (face == Direction.DOWN ? 0 : 1);
                z = baseZ + b;
            }
            case Z -> {
                x = baseX + a;
                y = baseY + b;
                z = baseZ + (face == Direction.NORTH ? 0 : 1);
            }
            default -> throw new IllegalStateException("Unexpected axis " + face.getAxis());
        }
        return new BlockPos(x, y, z);
    }

    private static BlockPos boundaryCell(
            BlockPos logicalFrameOffset,
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
        return new BlockPos(
                logicalFrameOffset.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + x,
                logicalFrameOffset.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + y,
                logicalFrameOffset.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + z);
    }

    private record ServerSupportView(
            MechanismAssembly assembly,
            FrameOrientation orientation,
            BlockPos logicalFrameOffset,
            Set<BlockPos> physicalFrames) {
    }
}
