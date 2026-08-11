package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serializable recovery journal for the eight mini cells owned by one frame.
 *
 * <p>The journal is created before any destructive write. A caller receiving
 * {@link FrameEvacuationService.Result#RECOVERY_REQUIRED} must retain this object
 * until every cell has been restored or the evacuation has been completed by an
 * explicit recovery workflow.</p>
 */
public final class PendingFrameEvacuation {
    public static final int CELL_COUNT = 8;

    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String FRAME_POSITION_TAG = "frame_position";
    private static final String CREATED_GAME_TIME_TAG = "created_game_time";
    private static final String CELLS_TAG = "cells";
    private static final String POSITION_TAG = "position";
    private static final String STATE_TAG = "state";
    private static final String BLOCK_ENTITY_TAG = "block_entity";

    private final UUID assemblyId;
    private final BlockPos framePosition;
    private final long createdGameTime;
    private final List<CellSnapshot> cells;

    public PendingFrameEvacuation(
            UUID assemblyId,
            BlockPos framePosition,
            long createdGameTime,
            List<CellSnapshot> cells) {
        this.assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
        this.framePosition = Objects.requireNonNull(framePosition, "framePosition").immutable();
        this.createdGameTime = createdGameTime;
        Objects.requireNonNull(cells, "cells");
        if (cells.size() != CELL_COUNT) {
            throw new IllegalArgumentException("A frame evacuation journal must contain exactly eight cells");
        }

        Set<BlockPos> uniquePositions = new HashSet<>();
        List<CellSnapshot> copiedCells = new ArrayList<>(CELL_COUNT);
        for (CellSnapshot cell : cells) {
            CellSnapshot copied = Objects.requireNonNull(cell, "cell").copy();
            if (!uniquePositions.add(copied.globalPosition())) {
                throw new IllegalArgumentException(
                        "Duplicate mini cell in frame evacuation journal: " + copied.globalPosition());
            }
            copiedCells.add(copied);
        }
        this.cells = List.copyOf(copiedCells);
    }

    public UUID assemblyId() {
        return assemblyId;
    }

    public BlockPos framePosition() {
        return framePosition;
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    public List<CellSnapshot> cells() {
        return cells;
    }

    public CompoundTag save() {
        return serializedJournal().save();
    }

    private SerializedJournal serializedJournal() {
        List<SerializedCellSnapshot> serializedCells = new ArrayList<>(CELL_COUNT);
        for (CellSnapshot cell : cells) {
            serializedCells.add(new SerializedCellSnapshot(
                    cell.globalPosition(),
                    NbtUtils.writeBlockState(cell.state()),
                    cell.blockEntityData()));
        }
        return new SerializedJournal(
                assemblyId,
                framePosition,
                createdGameTime,
                serializedCells);
    }

    /**
     * Registry-independent NBT envelope used both by disk loading and by plain unit tests.
     * Block-state registry resolution deliberately remains in {@link #load(CompoundTag, HolderGetter)}.
     */
    static record SerializedJournal(
            UUID assemblyId,
            BlockPos framePosition,
            long createdGameTime,
            List<SerializedCellSnapshot> cells) {
        SerializedJournal {
            assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
            framePosition = Objects.requireNonNull(framePosition, "framePosition").immutable();
            Objects.requireNonNull(cells, "cells");
            if (cells.size() != CELL_COUNT) {
                throw new IllegalArgumentException("A frame evacuation journal must contain exactly eight cells");
            }

            Set<BlockPos> uniquePositions = new HashSet<>();
            List<SerializedCellSnapshot> copiedCells = new ArrayList<>(CELL_COUNT);
            for (SerializedCellSnapshot cell : cells) {
                SerializedCellSnapshot copied = Objects.requireNonNull(cell, "cell").copy();
                if (!uniquePositions.add(copied.globalPosition())) {
                    throw new IllegalArgumentException(
                            "Duplicate mini cell in frame evacuation journal: " + copied.globalPosition());
                }
                copiedCells.add(copied);
            }
            cells = List.copyOf(copiedCells);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
            tag.putLong(FRAME_POSITION_TAG, framePosition.asLong());
            tag.putLong(CREATED_GAME_TIME_TAG, createdGameTime);

            ListTag cellList = new ListTag();
            for (SerializedCellSnapshot cell : cells) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putLong(POSITION_TAG, cell.globalPosition().asLong());
                cellTag.put(STATE_TAG, cell.stateData());
                CompoundTag blockEntityData = cell.blockEntityData();
                if (blockEntityData != null) {
                    cellTag.put(BLOCK_ENTITY_TAG, blockEntityData);
                }
                cellList.add(cellTag);
            }
            tag.put(CELLS_TAG, cellList);
            return tag;
        }

        static SerializedJournal load(CompoundTag tag) {
            Objects.requireNonNull(tag, "tag");
            if (!tag.hasUUID(ASSEMBLY_ID_TAG)
                    || !tag.contains(FRAME_POSITION_TAG, Tag.TAG_LONG)
                    || !tag.contains(CREATED_GAME_TIME_TAG, Tag.TAG_LONG)
                    || !tag.contains(CELLS_TAG, Tag.TAG_LIST)) {
                throw new IllegalArgumentException("Incomplete frame evacuation journal");
            }

            ListTag cellList = tag.getList(CELLS_TAG, Tag.TAG_COMPOUND);
            if (cellList.size() != CELL_COUNT) {
                throw new IllegalArgumentException("A frame evacuation journal must contain exactly eight cells");
            }

            List<SerializedCellSnapshot> cells = new ArrayList<>(CELL_COUNT);
            for (int index = 0; index < cellList.size(); index++) {
                CompoundTag cellTag = cellList.getCompound(index);
                if (!cellTag.contains(POSITION_TAG, Tag.TAG_LONG)
                        || !cellTag.contains(STATE_TAG, Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("Incomplete cell " + index + " in frame evacuation journal");
                }
                CompoundTag stateData = cellTag.getCompound(STATE_TAG);
                if (!stateData.contains("Name", Tag.TAG_STRING)
                        || ResourceLocation.tryParse(stateData.getString("Name")) == null) {
                    throw new IllegalArgumentException(
                            "Invalid block name in frame evacuation journal cell " + index);
                }
                CompoundTag blockEntityData = cellTag.contains(BLOCK_ENTITY_TAG, Tag.TAG_COMPOUND)
                        ? cellTag.getCompound(BLOCK_ENTITY_TAG)
                        : null;
                cells.add(new SerializedCellSnapshot(
                        BlockPos.of(cellTag.getLong(POSITION_TAG)),
                        stateData,
                        blockEntityData));
            }

            return new SerializedJournal(
                    tag.getUUID(ASSEMBLY_ID_TAG),
                    BlockPos.of(tag.getLong(FRAME_POSITION_TAG)),
                    tag.getLong(CREATED_GAME_TIME_TAG),
                    cells);
        }
    }

    static record SerializedCellSnapshot(
            BlockPos globalPosition,
            CompoundTag stateData,
            @Nullable CompoundTag blockEntityData) {
        SerializedCellSnapshot {
            globalPosition = Objects.requireNonNull(globalPosition, "globalPosition").immutable();
            stateData = Objects.requireNonNull(stateData, "stateData").copy();
            blockEntityData = blockEntityData == null ? null : blockEntityData.copy();
        }

        @Override
        public CompoundTag stateData() {
            return stateData.copy();
        }

        @Override
        public CompoundTag blockEntityData() {
            return blockEntityData == null ? null : blockEntityData.copy();
        }

        private SerializedCellSnapshot copy() {
            return new SerializedCellSnapshot(globalPosition, stateData, blockEntityData);
        }
    }

    /*
     * Kept as one registry-aware boundary: an unavailable mod block must fail recovery loudly
     * instead of being decoded by NbtUtils as air and silently losing the journaled cell.
     */
    public static PendingFrameEvacuation load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries.lookupOrThrow(Registries.BLOCK));
    }

    public static PendingFrameEvacuation load(CompoundTag tag, HolderGetter<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        SerializedJournal serialized = SerializedJournal.load(tag);
        List<CellSnapshot> cells = new ArrayList<>(CELL_COUNT);
        for (int index = 0; index < serialized.cells().size(); index++) {
            SerializedCellSnapshot serializedCell = serialized.cells().get(index);
            CompoundTag stateTag = serializedCell.stateData();
            ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
            if (blockId == null
                    || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown block in frame evacuation journal cell " + index + ": " + stateTag.getString("Name"));
            }
            BlockState state = NbtUtils.readBlockState(blocks, stateTag);
            if (!NbtUtils.writeBlockState(state).equals(stateTag)) {
                throw new IllegalArgumentException(
                        "Block state in frame evacuation journal cell " + index
                                + " cannot be decoded exactly: " + stateTag);
            }
            cells.add(new CellSnapshot(
                    serializedCell.globalPosition(),
                    state,
                    serializedCell.blockEntityData()));
        }

        return new PendingFrameEvacuation(
                serialized.assemblyId(),
                serialized.framePosition(),
                serialized.createdGameTime(),
                cells);
    }

    /*
     * Runtime snapshot. The NBT payload is copied both on construction and access because a
     * retained recovery journal must not be mutable through an event-owned block entity instance.
     */
    public record CellSnapshot(
            BlockPos globalPosition,
            BlockState state,
            @Nullable CompoundTag blockEntityData) {
        public CellSnapshot {
            globalPosition = Objects.requireNonNull(globalPosition, "globalPosition").immutable();
            state = Objects.requireNonNull(state, "state");
            blockEntityData = blockEntityData == null ? null : blockEntityData.copy();
        }

        @Override
        public CompoundTag blockEntityData() {
            return blockEntityData == null ? null : blockEntityData.copy();
        }

        private CellSnapshot copy() {
            return new CellSnapshot(globalPosition, state, blockEntityData);
        }
    }
}
