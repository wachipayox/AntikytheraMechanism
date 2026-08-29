package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.FrameSkin;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.sublevel.FrameMaskWriteGuard;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Portable, schematic-friendly snapshot of the eight logical mini cells owned by one Frame.
 *
 * <p>Normal world persistence keeps the authoritative mini blocks in Sable. This payload is an
 * intentionally redundant transport copy stored in the Frame block entity NBT so generic structure
 * formats (vanilla structures, Litematica/Forgematica, etc.) that know nothing about Sable can still
 * carry the Frame's contents. It is only applied when the loaded Frame no longer matches the
 * assembly mapping at its destination; ordinary chunk loading therefore never replays the snapshot.
 */
public final class PortableFrameContent {
    public static final String FRAME_NBT_TAG = "antikythera_portable_mini_content";

    private static final int VERSION = 1;
    private static final String VERSION_TAG = "version";
    private static final String HAS_CONTENT_TAG = "has_content";
    private static final String SHELL_MODE_TAG = "shell_mode";
    private static final String SKIN_TAG = "skin";
    private static final String CELLS_TAG = "cells";
    private static final String CELL_INDEX_TAG = "cell_index";
    private static final String STATE_TAG = "state";
    private static final String BLOCK_ENTITY_TAG = "block_entity";
    private static final int INTERNAL_UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private final FrameShellMode shellMode;
    private final FrameSkin skin;
    private final boolean hasContent;
    private final List<Cell> cells;

    private PortableFrameContent(
            FrameShellMode shellMode,
            FrameSkin skin,
            boolean hasContent,
            List<Cell> cells) {
        this.shellMode = Objects.requireNonNull(shellMode, "shellMode");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.hasContent = hasContent;
        this.cells = List.copyOf(cells);
        if (hasContent && cells.size() != PendingFrameEvacuation.CELL_COUNT) {
            throw new IllegalArgumentException("Portable Frame content must contain exactly eight cells");
        }
        if (!hasContent && !cells.isEmpty()) {
            throw new IllegalArgumentException("Empty portable Frame content cannot contain cell payloads");
        }
    }

    public FrameShellMode shellMode() {
        return shellMode;
    }

    public FrameSkin skin() {
        return skin;
    }

    public boolean hasContent() {
        return hasContent;
    }

    public static CompoundTag capture(ServerLevel level, MechanismAssembly assembly, BlockPos framePos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(framePos, "framePos");

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null && assembly.subLevelId() != null) {
            throw new IllegalStateException(
                    "Assembly " + assembly.id() + " references unavailable mini SubLevel " + assembly.subLevelId());
        }

        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION_TAG, VERSION);
        tag.putString(SHELL_MODE_TAG, assembly.shellMode().getSerializedName());
        tag.putString(SKIN_TAG, assembly.skin().serializedName());
        tag.putBoolean(HAS_CONTENT_TAG, subLevel != null);
        if (subLevel == null) {
            return tag;
        }

        ListTag cells = new ListTag();
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    int index = MiniCoordinateMapper.cellIndex(x, y, z);
                    BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
                    BlockPos storagePos = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                    BlockState state = level.getBlockState(storagePos);
                    BlockEntity blockEntity = level.getBlockEntity(storagePos);

                    CompoundTag cell = new CompoundTag();
                    cell.putByte(CELL_INDEX_TAG, (byte) index);
                    cell.put(STATE_TAG, NbtUtils.writeBlockState(state));
                    if (blockEntity != null) {
                        cell.put(BLOCK_ENTITY_TAG, blockEntity.saveWithFullMetadata(level.registryAccess()));
                    }
                    cells.add(cell);
                }
            }
        }
        tag.put(CELLS_TAG, cells);
        return tag;
    }

    public static PortableFrameContent load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries.lookupOrThrow(Registries.BLOCK));
    }

    static PortableFrameContent load(CompoundTag tag, HolderGetter<Block> blocks) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(blocks, "blocks");
        if (!tag.contains(VERSION_TAG, Tag.TAG_INT) || tag.getInt(VERSION_TAG) != VERSION) {
            throw new IllegalArgumentException("Unsupported portable Frame content version");
        }

        FrameShellMode shellMode = FrameShellMode.fromSerializedName(tag.getString(SHELL_MODE_TAG));
        FrameSkin skin = FrameSkin.fromSerializedName(tag.getString(SKIN_TAG));
        boolean hasContent = tag.getBoolean(HAS_CONTENT_TAG);
        if (!hasContent) {
            return new PortableFrameContent(shellMode, skin, false, List.of());
        }
        if (!tag.contains(CELLS_TAG, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Portable Frame content is missing its cell list");
        }

        ListTag cellTags = tag.getList(CELLS_TAG, Tag.TAG_COMPOUND);
        if (cellTags.size() != PendingFrameEvacuation.CELL_COUNT) {
            throw new IllegalArgumentException("Portable Frame content must contain exactly eight cells");
        }

        Cell[] ordered = new Cell[PendingFrameEvacuation.CELL_COUNT];
        for (int ordinal = 0; ordinal < cellTags.size(); ordinal++) {
            CompoundTag cellTag = cellTags.getCompound(ordinal);
            int index = Byte.toUnsignedInt(cellTag.getByte(CELL_INDEX_TAG));
            if (index >= ordered.length || ordered[index] != null || !cellTag.contains(STATE_TAG, Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid portable Frame cell entry at ordinal " + ordinal);
            }

            CompoundTag stateTag = cellTag.getCompound(STATE_TAG);
            if (!stateTag.contains("Name", Tag.TAG_STRING)) {
                throw new IllegalArgumentException("Portable Frame cell " + index + " has no block id");
            }
            ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
            if (blockId == null || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown block in portable Frame cell " + index + ": " + stateTag.getString("Name"));
            }
            BlockState state = NbtUtils.readBlockState(blocks, stateTag);
            if (!NbtUtils.writeBlockState(state).equals(stateTag)) {
                throw new IllegalArgumentException(
                        "Block state in portable Frame cell " + index + " cannot be decoded exactly");
            }
            CompoundTag blockEntityTag = cellTag.contains(BLOCK_ENTITY_TAG, Tag.TAG_COMPOUND)
                    ? cellTag.getCompound(BLOCK_ENTITY_TAG).copy()
                    : null;
            ordered[index] = new Cell(index, state, blockEntityTag);
        }

        List<Cell> cells = new ArrayList<>(ordered.length);
        for (int index = 0; index < ordered.length; index++) {
            if (ordered[index] == null) {
                throw new IllegalArgumentException("Portable Frame content is missing cell " + index);
            }
            cells.add(ordered[index]);
        }
        return new PortableFrameContent(shellMode, skin, true, cells);
    }

    public boolean restore(ServerLevel level, MechanismAssembly assembly, BlockPos framePos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(framePos, "framePos");
        if (!hasContent) {
            return true;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.ensureForContent(level, assembly);
        if (subLevel == null) {
            return false;
        }

        boolean writesSucceeded = FrameMaskWriteGuard.getBypassing(() -> {
            boolean success = true;
            for (Cell cell : cells) {
                int x = cell.index() & 1;
                int y = cell.index() >> 1 & 1;
                int z = cell.index() >> 2 & 1;
                BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, framePos, x, y, z);
                BlockPos storagePos = MechanismSubLevelService.toPlotPosition(subLevel, mini);
                success &= restoreCell(level, storagePos, cell.state(), cell.blockEntityData());
            }
            return success;
        });
        if (!writesSucceeded) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not fully restore portable mini content for Frame {} in assembly {}",
                    framePos,
                    assembly.id());
        }
        return writesSucceeded;
    }

    private static boolean restoreCell(
            ServerLevel level,
            BlockPos position,
            BlockState state,
            @Nullable CompoundTag blockEntityData) {
        try {
            Clearable.tryClear(level.getBlockEntity(position));
            level.setBlock(position, Blocks.AIR.defaultBlockState(), INTERNAL_UPDATE_FLAGS);
            if (state.isAir()) {
                return blockEntityData == null
                        && level.getBlockState(position).isAir()
                        && level.getBlockEntity(position) == null;
            }

            level.setBlock(position, state, INTERNAL_UPDATE_FLAGS);
            if (!state.equals(level.getBlockState(position))) {
                return false;
            }
            if (blockEntityData == null) {
                if (level.getBlockEntity(position) != null) {
                    level.removeBlockEntity(position);
                }
                return level.getBlockEntity(position) == null;
            }

            BlockEntity restored = level.getBlockEntity(position);
            if (restored == null) {
                return false;
            }
            CompoundTag relocated = blockEntityData.copy();
            relocated.putInt("x", position.getX());
            relocated.putInt("y", position.getY());
            relocated.putInt("z", position.getZ());
            restored.loadWithComponents(relocated, level.registryAccess());
            restored.setChanged();
            return true;
        } catch (RuntimeException exception) {
            AntikytheraMechanism.LOGGER.error("Could not restore portable mini cell {}", position, exception);
            return false;
        }
    }

    private record Cell(int index, BlockState state, @Nullable CompoundTag blockEntityData) {
        private Cell {
            Objects.requireNonNull(state, "state");
            blockEntityData = blockEntityData == null ? null : blockEntityData.copy();
        }

        @Override
        public @Nullable CompoundTag blockEntityData() {
            return blockEntityData == null ? null : blockEntityData.copy();
        }
    }
}
