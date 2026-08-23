package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PendingContraptionMove;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;

/**
 * Temporary mini -> macro support authority while Create has removed a source Frame or has not yet
 * written a prepared destination Frame.
 *
 * <p>Vanilla support checks are allowed to observe AIR at either physical Frame coordinate during a
 * contraption transaction. The durable {@link PendingContraptionMove} remains the structural truth
 * for that narrow interval. Support is projected only toward a macro boundary block that Create
 * actually captured beside the same Frame, preventing the journal from manufacturing support for
 * stationary world blocks.</p>
 */
public final class CreateContraptionFrameSupport {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;

    private CreateContraptionFrameSupport() {
    }

    /** @return null when this is not a live Create transition support query. */
    public static @Nullable Boolean query(
            ServerLevel level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);

        CreateAssemblyPlacementContext.Target target =
                CreateAssemblyPlacementContext.targetAt(level, framePosition);
        if (target != null) {
            return queryPreparedTarget(manager, level, framePosition, outwardFace, supportType, target);
        }

        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
        if (assembly == null && level.getBlockState(framePosition).isAir()) {
            assembly = releasedSourceAssembly(manager, framePosition);
        }
        if (assembly == null) {
            return null;
        }
        PendingContraptionMove move = manager.pendingContraptionMove(assembly.id()).orElse(null);
        if (move == null || !move.sourceFrames().contains(framePosition)) {
            return null;
        }
        if (manager.isContentRecoveryLocked(assembly.id()) || manager.pendingPistonMove(assembly.id()).isPresent()) {
            return false;
        }

        FrameOrientation sourceOrientation = FrameOrientation.fromQuaternion(
                move.startPose().orientation(new Quaterniond())).orElse(null);
        if (sourceOrientation == null || !sourceOrientation.equals(assembly.orientation())) {
            return false;
        }
        if (move.sourceFrames().contains(framePosition.relative(outwardFace))) {
            return false;
        }
        if (move.carriedBoundaryStateAtSource(framePosition.relative(outwardFace)).isEmpty()) {
            return null;
        }

        return queryMiniFace(
                level,
                assembly,
                sourceOrientation,
                assembly.logicalFrameOffset(framePosition),
                outwardFace,
                supportType);
    }

    /**
     * Resolves only the historical owner recorded by a live contraption journal after extraction has
     * explicitly released the source from frameIndex. This is deliberately not a general ownership
     * fallback: a replacement Frame owner always wins through getAssemblyAt, and a non-AIR source is
     * never allowed to inherit stale journal support.
     */
    private static @Nullable MechanismAssembly releasedSourceAssembly(
            MechanismAssemblyManager manager,
            BlockPos framePosition) {
        MechanismAssembly matched = null;
        for (MechanismAssembly candidate : manager.assemblies()) {
            PendingContraptionMove move = manager.pendingContraptionMove(candidate.id()).orElse(null);
            if (move == null || !move.isSourceReleased(framePosition)) {
                continue;
            }
            if (!move.sourceFrames().contains(framePosition)
                    || !candidate.frames().equals(move.sourceFrames())) {
                return null;
            }
            if (matched != null && !matched.id().equals(candidate.id())) {
                return null;
            }
            matched = candidate;
        }
        return matched;
    }

    private static @Nullable Boolean queryPreparedTarget(
            MechanismAssemblyManager manager,
            ServerLevel level,
            BlockPos framePosition,
            Direction outwardFace,
            SupportType supportType,
            CreateAssemblyPlacementContext.Target target) {
        MechanismAssembly assembly = manager.getAssembly(target.assemblyId()).orElse(null);
        if (assembly == null) {
            return false;
        }

        PendingContraptionMove move = manager.pendingContraptionMove(target.assemblyId()).orElse(null);
        if (move == null || !move.hasPlacement()) {
            // The synchronous placement context outlives the durable journal by a few calls: Create's
            // RETURN injection commits the assembly and reconnects neighbours before WrapMethod's
            // finally unwinds this context. Once the journal is gone, the destination Frame and
            // frameIndex are authoritative again; returning false here would manufacture one final
            // no-support pulse during reconnect and pop carried levers/buttons/torches back off.
            return null;
        }
        if (manager.isContentRecoveryLocked(target.assemblyId())
                || manager.pendingPistonMove(target.assemblyId()).isPresent()) {
            return false;
        }

        FrameOrientation targetOrientation = FrameOrientation.fromQuaternion(
                move.finalPose().orientation(new Quaterniond())).orElse(null);
        FrameOrientation sourceOrientation = FrameOrientation.fromQuaternion(
                move.startPose().orientation(new Quaterniond())).orElse(null);
        if (targetOrientation == null
                || sourceOrientation == null
                || !targetOrientation.equals(target.orientation())
                || !move.targetFrames().equals(target.targetFrames())
                || !move.targetFrames().contains(framePosition)
                || move.targetFrames().contains(framePosition.relative(outwardFace))
                || !target.logicalFrameOffset().equals(
                        targetOrientation.toLogical(framePosition.subtract(move.targetOrigin())))
                || !pendingTargetIsDocked(move, targetOrientation)
                || !MechanismAssemblyHost.sameResolvedHost(level, move.targetOrigin(), framePosition)) {
            return false;
        }

        // Match the destination physical face back to the source physical boundary through the
        // immutable logical frame/face basis. The captured source neighbour is proof that the macro
        // attachment itself belongs to this Create transaction.
        Direction logicalFace = targetOrientation.toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }
        BlockPos sourceFrame = move.sourceOrigin().offset(
                sourceOrientation.toPhysical(target.logicalFrameOffset()));
        Direction sourceOutwardFace = sourceOrientation.toPhysical(logicalFace);
        if (!move.sourceFrames().contains(sourceFrame)
                || move.carriedBoundaryStateAtSource(sourceFrame.relative(sourceOutwardFace)).isEmpty()) {
            return null;
        }

        return queryMiniFace(
                level,
                assembly,
                targetOrientation,
                target.logicalFrameOffset(),
                outwardFace,
                supportType);
    }

    private static boolean queryMiniFace(
            ServerLevel level,
            MechanismAssembly assembly,
            FrameOrientation orientation,
            BlockPos logicalFrameOffset,
            Direction outwardFace,
            SupportType supportType) {
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null || subLevel.isRemoved()) {
            return false;
        }

        Direction logicalFace = orientation.toLogical(outwardFace);
        if (logicalFace == null) {
            return false;
        }
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos mini = boundaryCell(logicalFrameOffset, logicalFace, a, b);
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

    private static BlockPos boundaryCell(
            BlockPos logicalFrameOffset,
            Direction logicalFace,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (logicalFace.getAxis()) {
            case X -> {
                x = logicalFace == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logicalFace == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logicalFace == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + logicalFace.getAxis());
        }
        return new BlockPos(
                logicalFrameOffset.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + x,
                logicalFrameOffset.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + y,
                logicalFrameOffset.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + z);
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
}
