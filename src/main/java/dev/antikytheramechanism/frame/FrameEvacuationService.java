package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.assembly.BlockSnapshotVerifier;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Transactionally extracts only the eight mini cells owned by one frame. */
public final class FrameEvacuationService {
    private static final int INTERNAL_UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private FrameEvacuationService() {
    }

    public static boolean evacuate(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePos,
            Cause cause) {
        return evacuateDetailed(level, assembly, framePos, cause).result() == Result.SUCCESS;
    }

    public static DetailedResult evacuateDetailed(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePos,
            Cause cause) {
        AssemblyLifecycleEvents.EvacuationTransaction lifecycle = AssemblyLifecycleEvents.beginEvacuation(
                new AssemblyLifecycleListener.FrameEvacuationContext(
                        level,
                        assembly,
                        framePos,
                        switch (cause.type()) {
                            case PLAYER -> AssemblyLifecycleListener.EvacuationReason.PLAYER;
                            case EXPLOSION -> AssemblyLifecycleListener.EvacuationReason.EXPLOSION;
                            case GENERIC -> AssemblyLifecycleListener.EvacuationReason.GENERIC;
                        }));
        if (!lifecycle.approved()) {
            return DetailedResult.rolledBack();
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            if (assembly.subLevelId() != null) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not evacuate frame {} from assembly {} because referenced SubLevel {} is unavailable",
                        framePos,
                        assembly.id(),
                        assembly.subLevelId());
                lifecycle.rollback(true);
                return DetailedResult.rolledBack();
            }

            // Canonical lazy-empty state: the Frame graph exists but there is no physical mini world.
            // There are no cells, block entities or scheduled plot ticks to evacuate.
            if (!lifecycle.complete()) {
                lifecycle.rollback(true);
                return DetailedResult.rolledBack();
            }
            return DetailedResult.success();
        }

        List<PendingFrameEvacuation.CellSnapshot> snapshots = new ArrayList<>(PendingFrameEvacuation.CELL_COUNT);
        List<PostCommitBatch> postCommitBatches = new ArrayList<>();
        try {
            gatherSnapshotsAndDrops(level, assembly, framePos, cause, subLevel, snapshots, postCommitBatches);
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not prepare evacuation of frame {} from assembly {}; source was left intact",
                    framePos,
                    assembly.id(),
                    exception);
            lifecycle.rollback(true);
            return DetailedResult.rolledBack();
        }

        PendingFrameEvacuation journal = new PendingFrameEvacuation(
                assembly.id(),
                framePos,
                level.getGameTime(),
                snapshots);

        boolean clearCompleted = clearCells(level, journal);
        if (!clearCompleted || !allCellsEmpty(level, journal)) {
            return rollback(level, journal, lifecycle);
        }

        if (!lifecycle.complete()) {
            return rollback(level, journal, lifecycle);
        }

        try {
            clearScheduledTicks(level, journal);
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Scheduled-tick cleanup failed after clearing frame {} from assembly {}; retaining a recovery journal",
                    framePos,
                    assembly.id(),
                    exception);
            restoreSnapshot(level, journal);
            return DetailedResult.recoveryRequired(journal);
        }

        runPostCommit(level, postCommitBatches);
        LazySubLevelLifecycle.requestRetirementCheck(level, assembly.id());
        return DetailedResult.success();
    }

    private static void gatherSnapshotsAndDrops(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePos,
            Cause cause,
            ServerSubLevel subLevel,
            List<PendingFrameEvacuation.CellSnapshot> snapshots,
            List<PostCommitBatch> postCommitBatches) {
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
                    BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                    BlockState state = level.getBlockState(global);
                    BlockEntity blockEntity = level.getBlockEntity(global);
                    CompoundTag blockEntityTag = blockEntity == null
                            ? null
                            : blockEntity.saveWithFullMetadata(level.registryAccess());
                    snapshots.add(new PendingFrameEvacuation.CellSnapshot(global, state, blockEntityTag));
                    if (state.isAir()) {
                        continue;
                    }

                    Vec3 visualPosition = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(global));
                    List<ItemStack> stacks = new ArrayList<>();
                    boolean harvestable = cause.breaker() == null
                            || cause.type() != CauseType.PLAYER
                            || state.canHarvestBlock(level, global, cause.breaker());
                    if (harvestable) {
                        stacks.addAll(Block.getDrops(
                                state,
                                level,
                                global,
                                blockEntity,
                                cause.breaker(),
                                cause.tool()));
                    }
                    if (blockEntity instanceof Container container) {
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack contained = container.getItem(slot);
                            if (!contained.isEmpty()) {
                                stacks.add(contained.copy());
                            }
                        }
                    }

                    List<ItemEntity> entities = stacks.stream()
                            .filter(stack -> !stack.isEmpty())
                            .map(stack -> new ItemEntity(
                                    level,
                                    visualPosition.x,
                                    visualPosition.y,
                                    visualPosition.z,
                                    stack.copy()))
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

                    if (cause.type() == CauseType.PLAYER) {
                        BlockDropsEvent dropsEvent = new BlockDropsEvent(
                                level,
                                global,
                                state,
                                blockEntity,
                                entities,
                                cause.breaker(),
                                cause.tool());
                        if (!harvestable) {
                            dropsEvent.setDroppedExperience(0);
                        }
                        NeoForge.EVENT_BUS.post(dropsEvent);
                        if (dropsEvent.isCanceled()) {
                            continue;
                        }
                        postCommitBatches.add(new PostCommitBatch(
                                List.copyOf(dropsEvent.getDrops()),
                                dropsEvent.getDroppedExperience(),
                                visualPosition,
                                state,
                                global,
                                cause.tool().copy()));
                    } else {
                        postCommitBatches.add(new PostCommitBatch(
                                List.copyOf(entities),
                                0,
                                visualPosition,
                                state,
                                global,
                                cause.tool().copy()));
                    }
                }
            }
        }
    }

    private static boolean clearCells(ServerLevel level, PendingFrameEvacuation journal) {
        return FrameMaskWriteGuard.getBypassing(() -> {
            boolean success = true;
            for (PendingFrameEvacuation.CellSnapshot snapshot : journal.cells()) {
                try {
                    Clearable.tryClear(level.getBlockEntity(snapshot.globalPosition()));
                    level.setBlock(
                            snapshot.globalPosition(),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                } catch (RuntimeException exception) {
                    success = false;
                    AntikytheraMechanism.LOGGER.error(
                            "Could not clear mini cell {} while evacuating frame {}",
                            snapshot.globalPosition(),
                            journal.framePosition(),
                            exception);
                }
            }
            return success;
        });
    }

    private static DetailedResult rollback(
            ServerLevel level,
            PendingFrameEvacuation journal,
            AssemblyLifecycleEvents.EvacuationTransaction lifecycle) {
        boolean contentRestored = restoreSnapshot(level, journal);
        boolean integrationRestored = lifecycle.rollback(contentRestored);
        return contentRestored && integrationRestored
                ? DetailedResult.rolledBack()
                : DetailedResult.recoveryRequired(journal);
    }

    private static boolean restoreSnapshot(ServerLevel level, PendingFrameEvacuation journal) {
        boolean writesSucceeded = FrameMaskWriteGuard.getBypassing(() -> {
            boolean success = true;
            for (PendingFrameEvacuation.CellSnapshot snapshot : journal.cells()) {
                success &= restoreCell(level, snapshot);
            }
            return success;
        });
        boolean structuralMatch = matchesSnapshot(level, journal);
        if (!writesSucceeded || !structuralMatch) {
            AntikytheraMechanism.LOGGER.error(
                    "CRITICAL: frame evacuation {} for assembly {} could not be restored structurally; its serializable recovery journal must be retained",
                    journal.framePosition(),
                    journal.assemblyId());
        }
        return writesSucceeded && structuralMatch;
    }

    private static boolean restoreCell(
            ServerLevel level,
            PendingFrameEvacuation.CellSnapshot snapshot) {
        BlockPos position = snapshot.globalPosition();
        try {
            Clearable.tryClear(level.getBlockEntity(position));
            level.setBlock(position, Blocks.AIR.defaultBlockState(), INTERNAL_UPDATE_FLAGS);
            if (snapshot.state().isAir()) {
                return snapshot.blockEntityData() == null
                        && level.getBlockState(position).isAir()
                        && level.getBlockEntity(position) == null;
            }
            level.setBlock(position, snapshot.state(), INTERNAL_UPDATE_FLAGS);
            if (!snapshot.state().equals(level.getBlockState(position))) {
                return false;
            }

            CompoundTag expectedBlockEntityData = snapshot.blockEntityData();
            if (expectedBlockEntityData == null) {
                if (level.getBlockEntity(position) != null) {
                    level.removeBlockEntity(position);
                }
                return level.getBlockEntity(position) == null;
            }
            BlockEntity restored = level.getBlockEntity(position);
            if (restored == null) {
                return false;
            }
            restored.loadWithComponents(relocatedTag(expectedBlockEntityData, position), level.registryAccess());
            restored.setChanged();
            return true;
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("Could not restore evacuated mini cell {}", position, exception);
            return false;
        }
    }

    private static boolean matchesSnapshot(ServerLevel level, PendingFrameEvacuation journal) {
        for (PendingFrameEvacuation.CellSnapshot snapshot : journal.cells()) {
            if (!BlockSnapshotVerifier.matches(
                    level,
                    snapshot.globalPosition(),
                    snapshot.state(),
                    snapshot.blockEntityData())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allCellsEmpty(ServerLevel level, PendingFrameEvacuation journal) {
        for (PendingFrameEvacuation.CellSnapshot snapshot : journal.cells()) {
            BlockPos position = snapshot.globalPosition();
            if (!level.getBlockState(position).isAir() || level.getBlockEntity(position) != null) {
                return false;
            }
        }
        return true;
    }

    private static CompoundTag relocatedTag(CompoundTag original, BlockPos position) {
        CompoundTag relocated = original.copy();
        relocated.putInt("x", position.getX());
        relocated.putInt("y", position.getY());
        relocated.putInt("z", position.getZ());
        return relocated;
    }

    private static void clearScheduledTicks(ServerLevel level, PendingFrameEvacuation journal) {
        BlockPos min = null;
        BlockPos max = null;
        for (PendingFrameEvacuation.CellSnapshot snapshot : journal.cells()) {
            BlockPos position = snapshot.globalPosition();
            min = min == null
                    ? position
                    : new BlockPos(
                            Math.min(min.getX(), position.getX()),
                            Math.min(min.getY(), position.getY()),
                            Math.min(min.getZ(), position.getZ()));
            max = max == null
                    ? position
                    : new BlockPos(
                            Math.max(max.getX(), position.getX()),
                            Math.max(max.getY(), position.getY()),
                            Math.max(max.getZ(), position.getZ()));
        }
        BoundingBox evacuatedArea = BoundingBox.fromCorners(min, max);
        level.getBlockTicks().clearArea(evacuatedArea);
        level.getFluidTicks().clearArea(evacuatedArea);
    }

    private static void runPostCommit(ServerLevel level, List<PostCommitBatch> batches) {
        for (PostCommitBatch batch : batches) {
            for (ItemEntity item : batch.items()) {
                try {
                    if (!level.addFreshEntity(item)) {
                        AntikytheraMechanism.LOGGER.error(
                                "Could not spawn committed evacuation drop {} at {}",
                                item.getItem(),
                                item.position());
                    }
                } catch (RuntimeException exception) {
                    AntikytheraMechanism.LOGGER.error(
                            "Could not spawn a committed evacuation drop at {}",
                            item.position(),
                            exception);
                }
            }

            try {
                batch.state().spawnAfterBreak(level, batch.blockPosition(), batch.tool(), false);
            } catch (RuntimeException exception) {
                AntikytheraMechanism.LOGGER.error(
                        "Post-break callback failed after committed evacuation at {}",
                        batch.blockPosition(),
                        exception);
            }

            if (batch.experience() > 0) {
                try {
                    ExperienceOrb.award(level, batch.visualPosition(), batch.experience());
                } catch (RuntimeException exception) {
                    AntikytheraMechanism.LOGGER.error(
                            "Could not spawn committed evacuation experience at {}",
                            batch.visualPosition(),
                            exception);
                }
            }
        }
    }

    public enum Result {
        SUCCESS,
        ROLLED_BACK,
        RECOVERY_REQUIRED
    }

    public record DetailedResult(Result result, @Nullable PendingFrameEvacuation recoveryJournal) {
        public DetailedResult {
            Objects.requireNonNull(result, "result");
            if ((result == Result.RECOVERY_REQUIRED) != (recoveryJournal != null)) {
                throw new IllegalArgumentException(
                        "A recovery journal is required exactly when evacuation recovery is required");
            }
        }

        public static DetailedResult success() {
            return new DetailedResult(Result.SUCCESS, null);
        }

        public static DetailedResult rolledBack() {
            return new DetailedResult(Result.ROLLED_BACK, null);
        }

        public static DetailedResult recoveryRequired(PendingFrameEvacuation journal) {
            return new DetailedResult(Result.RECOVERY_REQUIRED, journal);
        }
    }

    public enum CauseType {
        PLAYER,
        EXPLOSION,
        GENERIC
    }

    public record Cause(CauseType type, @Nullable Player breaker, ItemStack tool) {
        public Cause {
            tool = tool.copy();
        }

        public static Cause player(Player player, ItemStack tool) {
            return new Cause(CauseType.PLAYER, player, tool);
        }

        public static Cause explosion() {
            return new Cause(CauseType.EXPLOSION, null, ItemStack.EMPTY);
        }

        public static Cause generic() {
            return new Cause(CauseType.GENERIC, null, ItemStack.EMPTY);
        }
    }

    private record PostCommitBatch(
            List<ItemEntity> items,
            int experience,
            Vec3 visualPosition,
            BlockState state,
            BlockPos blockPosition,
            ItemStack tool) {
    }
}
