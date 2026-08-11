package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingFrameEvacuationTest {
    @Test
    void serializedJournalRoundTripsEightExactStateAndBlockEntityCompounds() {
        PendingFrameEvacuation.SerializedJournal original = serializedJournal();

        PendingFrameEvacuation.SerializedJournal loaded =
                PendingFrameEvacuation.SerializedJournal.load(original.save());

        assertEquals(original.assemblyId(), loaded.assemblyId());
        assertEquals(original.framePosition(), loaded.framePosition());
        assertEquals(original.createdGameTime(), loaded.createdGameTime());
        assertEquals(original.save(), loaded.save());
        assertEquals(
                "west",
                loaded.cells().getFirst().stateData().getCompound("Properties").getString("facing"));
        assertEquals(
                "journal chest",
                loaded.cells().get(1).blockEntityData().getString("CustomName"));
    }

    @Test
    void serializedEnvelopeDoesNotExposeMutableStateOrBlockEntityData() {
        PendingFrameEvacuation.SerializedJournal journal = serializedJournal();
        CompoundTag exposedState = journal.cells().getFirst().stateData();
        CompoundTag exposedBlockEntity = journal.cells().get(1).blockEntityData();
        exposedState.putString("Name", "minecraft:air");
        exposedBlockEntity.putString("CustomName", "mutated");

        assertNotEquals("minecraft:air", journal.cells().getFirst().stateData().getString("Name"));
        assertNotEquals("mutated", journal.cells().get(1).blockEntityData().getString("CustomName"));

        CompoundTag serialized = journal.save();
        serialized.getList("cells", Tag.TAG_COMPOUND)
                .getCompound(1)
                .getCompound("block_entity")
                .putString("CustomName", "serialized mutation");
        assertNotEquals("serialized mutation", journal.cells().get(1).blockEntityData().getString("CustomName"));
    }

    @Test
    void rejectsDuplicateOrMissingCellsInMemoryAndOnDisk() {
        PendingFrameEvacuation.SerializedJournal original = serializedJournal();
        List<PendingFrameEvacuation.SerializedCellSnapshot> seven = original.cells().subList(0, 7);
        assertThrows(IllegalArgumentException.class, () -> new PendingFrameEvacuation.SerializedJournal(
                original.assemblyId(), original.framePosition(), original.createdGameTime(), seven));

        List<PendingFrameEvacuation.SerializedCellSnapshot> duplicate = new ArrayList<>(original.cells());
        duplicate.set(7, duplicate.getFirst());
        assertThrows(IllegalArgumentException.class, () -> new PendingFrameEvacuation.SerializedJournal(
                original.assemblyId(), original.framePosition(), original.createdGameTime(), duplicate));

        CompoundTag sevenOnDisk = original.save();
        ListTag persistedCells = sevenOnDisk.getList("cells", Tag.TAG_COMPOUND);
        persistedCells.remove(persistedCells.size() - 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> PendingFrameEvacuation.SerializedJournal.load(sevenOnDisk));
    }

    @Test
    void rejectsIncompleteOrMalformedStateEnvelopeWithoutRegistryBootstrap() {
        CompoundTag missingTimestamp = serializedJournal().save();
        missingTimestamp.remove("created_game_time");
        assertThrows(
                IllegalArgumentException.class,
                () -> PendingFrameEvacuation.SerializedJournal.load(missingTimestamp));

        CompoundTag missingBlockName = serializedJournal().save();
        missingBlockName.getList("cells", Tag.TAG_COMPOUND)
                .getCompound(0)
                .getCompound("state")
                .remove("Name");
        assertThrows(
                IllegalArgumentException.class,
                () -> PendingFrameEvacuation.SerializedJournal.load(missingBlockName));
    }

    private static PendingFrameEvacuation.SerializedJournal serializedJournal() {
        UUID assemblyId = UUID.fromString("d5eb3db0-7493-4678-936d-608d133305ff");
        BlockPos framePosition = new BlockPos(-4, 12, 9);
        BlockPos plotOrigin = new BlockPos(1_000, 40, -2_000);
        List<PendingFrameEvacuation.SerializedCellSnapshot> cells = new ArrayList<>();

        for (int index = 0; index < PendingFrameEvacuation.CELL_COUNT; index++) {
            int x = index & 1;
            int y = index >> 1 & 1;
            int z = index >> 2 & 1;
            BlockPos position = plotOrigin.offset(x, y, z);

            CompoundTag stateData = new CompoundTag();
            stateData.putString(
                    "Name",
                    index == 0
                            ? "minecraft:oak_stairs"
                            : index == 1
                                    ? "minecraft:chest"
                                    : index == 2 ? "minecraft:redstone_wire" : "minecraft:stone");
            if (index == 0) {
                CompoundTag properties = new CompoundTag();
                properties.putString("facing", "west");
                properties.putString("half", "bottom");
                properties.putString("shape", "straight");
                properties.putString("waterlogged", "true");
                stateData.put("Properties", properties);
            }

            CompoundTag blockEntityData = null;
            if (index == 1) {
                blockEntityData = new CompoundTag();
                blockEntityData.putString("id", "minecraft:chest");
                blockEntityData.putInt("x", position.getX());
                blockEntityData.putInt("y", position.getY());
                blockEntityData.putInt("z", position.getZ());
                blockEntityData.putString("CustomName", "journal chest");
                blockEntityData.put("Items", new ListTag());
            }
            cells.add(new PendingFrameEvacuation.SerializedCellSnapshot(position, stateData, blockEntityData));
        }

        return new PendingFrameEvacuation.SerializedJournal(
                assemblyId,
                framePosition,
                987_654L,
                cells);
    }
}
