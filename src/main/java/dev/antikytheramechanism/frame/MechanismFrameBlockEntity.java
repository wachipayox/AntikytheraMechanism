package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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

    /**
     * Full logical orientation used to map this Frame to its immutable mini region.
     *
     * <p>This may contain pitch/roll after a Create contraption rotates the physical Frame layout.
     * It is not the orientation of the placed block, whose state can only represent an upright
     * horizontal facing.</p>
     */
    public FrameOrientation getFrameOrientation() { return orientation; }

    /**
     * Orientation actually representable by the placed Frame BlockState.
     *
     * <p>Create's StructureTransform is the authority that chooses the final BlockState on
     * disassembly. Because the Frame exposes only HORIZONTAL_FACING, a static Frame is always Y-up;
     * pitch/roll remain purely part of the logical assembly mapping.</p>
     */
    public FrameOrientation getPhysicalFrameOrientation() {
        BlockState state = getBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return new FrameOrientation(Direction.UP, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return FrameOrientation.IDENTITY;
    }

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
        synchronizePlacedOriginPose();
    }

    /**
     * A split target can acquire mini content before its static origin Frame has been rebound from
     * the source UUID. The mapping write is the first point at which that placed Frame is a reliable
     * physical authority for the new child, so publish its Y-up pose immediately instead of waiting
     * for a reload to rebuild the managed SubLevel transform.
     */
    private void synchronizePlacedOriginPose() {
        if (!(level instanceof ServerLevel serverLevel)
                || assemblyId == null
                || !BlockPos.ZERO.equals(logicalFrameOffset)) {
            return;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(serverLevel)
                .getAssembly(assemblyId)
                .orElse(null);
        if (assembly == null || !worldPosition.equals(assembly.origin())) {
            return;
        }
        MechanismSubLevelService.synchronizePlacedPhysicalPose(serverLevel, assembly);
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
