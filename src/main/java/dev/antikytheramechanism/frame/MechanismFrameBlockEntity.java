package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public final class MechanismFrameBlockEntity extends BlockEntity {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String OCCUPIED_MASK_TAG = "occupied_mask";
    private static final String ORIENTATION_TAG = "frame_orientation";
    private static final String LOGICAL_OFFSET_TAG = "logical_frame_offset";
    private UUID assemblyId;
    private int occupiedMask;
    private FrameOrientation orientation = FrameOrientation.IDENTITY;
    private BlockPos logicalFrameOffset = BlockPos.ZERO;

    public MechanismFrameBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.MECHANISM_FRAME_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getAssemblyId() { return assemblyId; }

    public void setAssemblyId(UUID assemblyId) {
        if (java.util.Objects.equals(this.assemblyId, assemblyId)) return;
        this.assemblyId = assemblyId;
        markAndSynchronize();
    }

    public FrameOrientation getFrameOrientation() { return orientation; }
    public BlockPos getLogicalFrameOffset() { return logicalFrameOffset; }

    public void setAssemblyMapping(UUID assemblyId, FrameOrientation orientation, BlockPos logicalFrameOffset) {
        FrameOrientation safeOrientation = java.util.Objects.requireNonNull(orientation, "orientation");
        BlockPos safeOffset = java.util.Objects.requireNonNull(logicalFrameOffset, "logicalFrameOffset").immutable();
        if (java.util.Objects.equals(this.assemblyId, assemblyId)
                && this.orientation.equals(safeOrientation)
                && this.logicalFrameOffset.equals(safeOffset)) return;
        this.assemblyId = assemblyId;
        this.orientation = safeOrientation;
        this.logicalFrameOffset = safeOffset;
        markAndSynchronize();
    }

    public int getOccupiedMask() { return occupiedMask; }

    public void setOccupiedMask(int occupiedMask) {
        int sanitized = occupiedMask & 0xFF;
        if (this.occupiedMask == sanitized) return;
        this.occupiedMask = sanitized;
        markAndSynchronize();
    }

    private void markAndSynchronize() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        assemblyId = tag.hasUUID(ASSEMBLY_ID_TAG) ? tag.getUUID(ASSEMBLY_ID_TAG) : null;
        occupiedMask = tag.getInt(OCCUPIED_MASK_TAG) & 0xFF;
        orientation = tag.contains(ORIENTATION_TAG, Tag.TAG_COMPOUND)
                ? FrameOrientation.load(tag.getCompound(ORIENTATION_TAG)) : FrameOrientation.IDENTITY;
        logicalFrameOffset = tag.contains(LOGICAL_OFFSET_TAG, Tag.TAG_LONG)
                ? BlockPos.of(tag.getLong(LOGICAL_OFFSET_TAG)) : BlockPos.ZERO;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (assemblyId != null) tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putInt(OCCUPIED_MASK_TAG, occupiedMask);
        tag.put(ORIENTATION_TAG, orientation.save());
        tag.putLong(LOGICAL_OFFSET_TAG, logicalFrameOffset.asLong());
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
