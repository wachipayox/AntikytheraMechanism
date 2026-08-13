package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Moves original blocks between Sable plots without rotating their BlockStates. */
public final class AssemblyContentTransferService {
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(pos -> pos.getZ())
            .thenComparingInt(pos -> pos.getX());

    private AssemblyContentTransferService() {
    }

    public static TransferResult transferFrames(
            ServerLevel level,
            MechanismAssembly source,
            MechanismAssembly target,
            Collection<BlockPos> frames) {
        return transferFrames(level, source, target, frames, AssemblyLifecycleListener.TransferKind.TRANSFER);
    }

    public static TransferResult transferFrames(
            ServerLevel level,
            MechanismAssembly source,
            MechanismAssembly target,
            Collection<BlockPos> frames,
            AssemblyLifecycleListener.TransferKind kind) {
        if (frames.isEmpty()) return TransferResult.SUCCESS;
        if (!target.frames().containsAll(frames)) {
            throw new IllegalArgumentException("Target FrameMask must own every transferred frame");
        }

        List<BlockPos> orderedFrames = frames.stream().sorted(POSITION_ORDER).toList();
        AssemblyLifecycleEvents.TransferTransaction lifecycle = AssemblyLifecycleEvents.beginTransfer(
                new AssemblyLifecycleListener.AssemblyTransferContext(level, source, target, orderedFrames, kind));
        if (!lifecycle.approved()) {
            return lifecycle.rejectionCompensated() ? TransferResult.ROLLED_BACK : TransferResult.RECOVERY_REQUIRED;
        }

        ServerSubLevel sourceSubLevel = MechanismSubLevelService.findExisting(level, source);
        if (sourceSubLevel == null) {
            // A null logical id is the canonical lazy state: this assembly has no physical mini world.
            // A non-null unresolved id is different and may represent unavailable persisted payload;
            // fail closed rather than silently treating it as empty.
            if (source.subLevelId() != null) {
                AntikytheraMechanism.LOGGER.error(
                        "Refused assembly transfer {} -> {} because source SubLevel {} is unavailable",
                        source.id(), target.id(), source.subLevelId());
                return abort(lifecycle, true);
            }
            if (!lifecycle.complete()) return abort(lifecycle, true);
            return TransferResult.SUCCESS;
        }

        for (BlockPos frame : orderedFrames) {
            if (!MechanismSubLevelService.canAddressFrame(level, sourceSubLevel, source, frame)) {
                AntikytheraMechanism.LOGGER.error(
                        "Refused assembly transfer {} -> {} because source frame {} leaves its Sable plot or build height",
                        source.id(), target.id(), frame);
                return abort(lifecycle, true);
            }
        }

        List<BlockPos> sourcePositions = new ArrayList<>(orderedFrames.size() * 8);
        List<CellSnapshot> snapshots = new ArrayList<>(orderedFrames.size() * 8);
        boolean hasContent = false;

        for (BlockPos frame : orderedFrames) {
            for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                    for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                        BlockPos sourceMini = MiniCoordinateMapper.frameToMini(source, frame, x, y, z);
                        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(sourceSubLevel, sourceMini);
                        BlockState sourceState = level.getBlockState(sourceGlobal);
                        BlockEntity blockEntity = level.getBlockEntity(sourceGlobal);
                        CompoundTag blockEntityData = null;
                        if (blockEntity != null) {
                            try {
                                blockEntityData = blockEntity.saveWithFullMetadata(level.registryAccess());
                            } catch (RuntimeException exception) {
                                AntikytheraMechanism.LOGGER.error(
                                        "Refused assembly transfer {} -> {} because block entity {} at {} could not be snapshotted",
                                        source.id(), target.id(), blockEntity.getType(), sourceMini, exception);
                                return abort(lifecycle, true);
                            }
                        }
                        hasContent |= !sourceState.isAir() || blockEntity != null;
                        snapshots.add(new CellSnapshot(sourceState, blockEntityData));
                        sourcePositions.add(sourceGlobal.immutable());
                    }
                }
            }
        }

        if (!hasContent) {
            if (!lifecycle.complete()) return abort(lifecycle, true);
            ServerSubLevel existingTarget = MechanismSubLevelService.findExisting(level, target);
            if (existingTarget != null) {
                copyAndClearScheduledTicks(level, source, target, sourceSubLevel, existingTarget, orderedFrames);
            } else {
                clearScheduledTicksForFrames(level, source, sourceSubLevel, orderedFrames);
            }
            LazySubLevelLifecycle.requestRetirementCheck(level, source.id());
            return TransferResult.SUCCESS;
        }

        ServerSubLevel targetSubLevel = MechanismSubLevelService.ensureForContent(level, target);
        if (targetSubLevel == null) {
            return abort(lifecycle, true);
        }
        // If the transfer aborts before writing anything, do not retain its staging plot.
        LazySubLevelLifecycle.requestRetirementCheck(level, target.id());

        for (BlockPos frame : orderedFrames) {
            if (!MechanismSubLevelService.canAddressFrame(level, targetSubLevel, target, frame)) {
                AntikytheraMechanism.LOGGER.error(
                        "Refused assembly transfer {} -> {} because target frame {} would leave its Sable plot or build height",
                        source.id(), target.id(), frame);
                return abort(lifecycle, true);
            }
        }

        List<BlockPos> targetPositions = new ArrayList<>(sourcePositions.size());
        int cellIndex = 0;
        for (BlockPos frame : orderedFrames) {
            for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                    for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                        BlockPos targetMini = MiniCoordinateMapper.frameToMini(target, frame, x, y, z);
                        BlockPos targetGlobal = MechanismSubLevelService.toPlotPosition(targetSubLevel, targetMini);
                        if (!level.getBlockState(targetGlobal).isAir() || level.getBlockEntity(targetGlobal) != null) {
                            AntikytheraMechanism.LOGGER.error(
                                    "Refused assembly transfer {} -> {} because target {} is occupied",
                                    source.id(), target.id(), targetMini);
                            return abort(lifecycle, true);
                        }
                        targetPositions.add(targetGlobal.immutable());
                        cellIndex++;
                    }
                }
            }
        }
        if (cellIndex != snapshots.size()) {
            throw new IllegalStateException("Source/target transfer cell counts differ");
        }

        SubLevelAssemblyHelper.AssemblyTransform transform = new SubLevelAssemblyHelper.AssemblyTransform(
                sourcePositions.getFirst(), targetPositions.getFirst(), 0, Rotation.NONE, level);
        try {
            FrameMaskWriteGuard.runBypassing(() -> SubLevelAssemblyHelper.moveBlocks(level, transform, sourcePositions));
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Assembly transfer {} -> {} threw before verification; restoring its snapshot",
                    source.id(), target.id(), exception);
            return abort(lifecycle, restoreSnapshot(level, sourcePositions, targetPositions, snapshots));
        }

        if (!matchesSnapshots(level, targetPositions, snapshots) || !allCellsEmpty(level, sourcePositions)) {
            AntikytheraMechanism.LOGGER.error(
                    "Assembly transfer {} -> {} failed structural verification; restoring its snapshot",
                    source.id(), target.id());
            return abort(lifecycle, restoreSnapshot(level, sourcePositions, targetPositions, snapshots));
        }

        if (!lifecycle.complete()) {
            return abort(lifecycle, restoreSnapshot(level, sourcePositions, targetPositions, snapshots));
        }
        copyAndClearScheduledTicks(level, source, target, sourceSubLevel, targetSubLevel, orderedFrames);
        LazySubLevelLifecycle.requestRetirementCheck(level, source.id());
        return TransferResult.SUCCESS;
    }

    private static TransferResult abort(AssemblyLifecycleEvents.TransferTransaction lifecycle, boolean contentRestored) {
        boolean integrationRestored = lifecycle.rollback(contentRestored);
        return contentRestored && integrationRestored ? TransferResult.ROLLED_BACK : TransferResult.RECOVERY_REQUIRED;
    }

    private static boolean matchesSnapshots(ServerLevel level, List<BlockPos> positions, List<CellSnapshot> snapshots) {
        for (int index = 0; index < positions.size(); index++) {
            CellSnapshot expected = snapshots.get(index);
            if (!BlockSnapshotVerifier.matches(level, positions.get(index), expected.state(), expected.blockEntityData())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allCellsEmpty(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos position : positions) {
            if (!level.getBlockState(position).isAir() || level.getBlockEntity(position) != null) return false;
        }
        return true;
    }

    private static boolean restoreSnapshot(
            ServerLevel level,
            List<BlockPos> sourcePositions,
            List<BlockPos> targetPositions,
            List<CellSnapshot> snapshots) {
        boolean restored = FrameMaskWriteGuard.getBypassing(() -> {
            boolean success = true;
            for (BlockPos target : targetPositions) success &= clearCell(level, target);
            for (int index = 0; index < sourcePositions.size(); index++) {
                success &= restoreCell(level, sourcePositions.get(index), snapshots.get(index));
            }
            return success;
        });
        boolean sourceMatches = matchesSnapshots(level, sourcePositions, snapshots);
        boolean targetEmpty = allCellsEmpty(level, targetPositions);
        if (targetEmpty) clearScheduledTicks(level, targetPositions);
        if (!restored || !sourceMatches || !targetEmpty) {
            AntikytheraMechanism.LOGGER.error(
                    "CRITICAL: an assembly transfer snapshot could not be restored structurally; the affected SubLevels have been retained for manual recovery");
            return false;
        }
        return true;
    }

    private static void clearScheduledTicks(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos position : positions) {
            BoundingBox cell = BoundingBox.fromCorners(position, position);
            level.getBlockTicks().clearArea(cell);
            level.getFluidTicks().clearArea(cell);
        }
    }

    private static void clearScheduledTicksForFrames(
            ServerLevel level,
            MechanismAssembly source,
            ServerSubLevel sourceSubLevel,
            List<BlockPos> frames) {
        for (BlockPos frame : frames) {
            BlockPos sourceMin = MechanismSubLevelService.toPlotPosition(
                    sourceSubLevel, MiniCoordinateMapper.frameToMini(source, frame, 0, 0, 0));
            BlockPos sourceMax = sourceMin.offset(1, 1, 1);
            BoundingBox area = BoundingBox.fromCorners(sourceMin, sourceMax);
            level.getBlockTicks().clearArea(area);
            level.getFluidTicks().clearArea(area);
        }
    }

    private static boolean clearCell(ServerLevel level, BlockPos position) {
        try {
            Clearable.tryClear(level.getBlockEntity(position));
            level.setBlock(position, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            return level.getBlockState(position).isAir() && level.getBlockEntity(position) == null;
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("Could not clear rollback target cell {}", position, exception);
            return false;
        }
    }

    private static boolean restoreCell(ServerLevel level, BlockPos position, CellSnapshot snapshot) {
        try {
            Clearable.tryClear(level.getBlockEntity(position));
            level.setBlock(position, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            if (snapshot.state().isAir()) return snapshot.blockEntityData() == null;
            if (!level.setBlock(position, snapshot.state(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) return false;
            if (snapshot.blockEntityData() == null) return level.getBlockEntity(position) == null;
            BlockEntity restored = level.getBlockEntity(position);
            if (restored == null) return false;
            restored.loadWithComponents(relocatedTag(snapshot.blockEntityData(), position), level.registryAccess());
            restored.setChanged();
            return true;
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("Could not restore assembly snapshot cell {}", position, exception);
            return false;
        }
    }

    private static CompoundTag relocatedTag(CompoundTag original, BlockPos position) {
        CompoundTag relocated = original.copy();
        relocated.putInt("x", position.getX());
        relocated.putInt("y", position.getY());
        relocated.putInt("z", position.getZ());
        return relocated;
    }

    private static void copyAndClearScheduledTicks(
            ServerLevel level,
            MechanismAssembly source,
            MechanismAssembly target,
            ServerSubLevel sourceSubLevel,
            ServerSubLevel targetSubLevel,
            List<BlockPos> frames) {
        for (BlockPos frame : frames) {
            BlockPos sourceMin = MechanismSubLevelService.toPlotPosition(
                    sourceSubLevel, MiniCoordinateMapper.frameToMini(source, frame, 0, 0, 0));
            BlockPos sourceMax = sourceMin.offset(1, 1, 1);
            BlockPos targetMin = MechanismSubLevelService.toPlotPosition(
                    targetSubLevel, MiniCoordinateMapper.frameToMini(target, frame, 0, 0, 0));
            Vec3i offset = targetMin.subtract(sourceMin);
            BoundingBox area = BoundingBox.fromCorners(sourceMin, sourceMax);
            level.getBlockTicks().copyArea(area, offset);
            level.getFluidTicks().copyArea(area, offset);
            level.getBlockTicks().clearArea(area);
            level.getFluidTicks().clearArea(area);
        }
    }

    private record CellSnapshot(BlockState state, CompoundTag blockEntityData) {
    }

    public enum TransferResult {
        SUCCESS,
        ROLLED_BACK,
        RECOVERY_REQUIRED
    }
}
