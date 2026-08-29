package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.FrameSkin;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;

import java.util.Objects;
import java.util.UUID;

public final class MechanismFrameBlockEntity extends BlockEntity {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String OCCUPIED_MASK_TAG = "occupied_mask";
    private static final String ORIENTATION_TAG = "frame_orientation";
    private static final String LOGICAL_OFFSET_TAG = "logical_frame_offset";
    private static final String PRESENTATION_SKIN_TAG = "presentation_skin";
    private static final int MAX_PORTABLE_RESTORE_ATTEMPTS = 40;

    private UUID assemblyId;
    private int occupiedMask;
    private FrameOrientation orientation = FrameOrientation.IDENTITY;
    private BlockPos logicalFrameOffset = BlockPos.ZERO;
    private FrameSkin presentationSkin = FrameSkin.COPPER;
    private @Nullable CompoundTag portableMiniContent;
    private boolean portableRestorePending;
    private int portableRestoreAttempts;

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
     * Persistent static yaw used to map this Frame to its immutable mini region.
     *
     * <p>Pitch and roll are never stored here. While Create is moving the assembly those degrees of
     * freedom live exclusively in AssemblyPose; after placement this value is canonicalized to the
     * horizontal facing represented by the placed Frame.</p>
     */
    public FrameOrientation getFrameOrientation() { return orientation; }

    /** Orientation actually represented by the placed Frame BlockState. */
    public FrameOrientation getPhysicalFrameOrientation() {
        BlockState state = getBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return new FrameOrientation(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return FrameOrientation.IDENTITY;
    }

    public BlockPos getLogicalFrameOffset() { return logicalFrameOffset; }

    public void setAssemblyMapping(UUID assemblyId, FrameOrientation orientation, BlockPos logicalFrameOffset) {
        FrameOrientation safeOrientation = java.util.Objects.requireNonNull(orientation, "orientation");
        BlockPos safeOffset = java.util.Objects.requireNonNull(logicalFrameOffset, "logicalFrameOffset").immutable();
        if (java.util.Objects.equals(this.assemblyId, assemblyId)
                && this.orientation.equals(safeOrientation)
                && this.logicalFrameOffset.equals(safeOffset)) {
            synchronizePlacedOriginPose();
            return;
        }
        this.assemblyId = assemblyId;
        this.orientation = safeOrientation;
        this.logicalFrameOffset = safeOffset;
        markAndSynchronize();
        synchronizePlacedOriginPose();
    }

    private void synchronizePlacedOriginPose() {
        if (!(level instanceof ServerLevel serverLevel)
                || assemblyId == null
                || !BlockPos.ZERO.equals(logicalFrameOffset)) {
            return;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
        if (assembly == null || !worldPosition.equals(assembly.origin())) {
            return;
        }

        FrameOrientation physicalOrientation = getPhysicalFrameOrientation();
        if (assembly.frames().size() == 1 && !assembly.orientation().equals(physicalOrientation)) {
            assembly.setOrientation(physicalOrientation);
            if (!orientation.equals(physicalOrientation)) {
                orientation = physicalOrientation;
                markAndSynchronize();
            }
            manager.setDirty();
        }

        FrameOrientation staticOrientation = assembly.orientation();
        AssemblyPose semanticPose = assembly.poseTarget();
        Quaterniond q = staticOrientation.quaternion(new Quaterniond());
        AssemblyPose canonicalPose = new AssemblyPose(
                semanticPose.anchorX(), semanticPose.anchorY(), semanticPose.anchorZ(),
                q.x, q.y, q.z, q.w);
        if (!semanticPose.approximatelyEquals(canonicalPose, 1.0E-10)) {
            assembly.setPoseTarget(canonicalPose);
            manager.setDirty();
        }

        MechanismSubLevelService.synchronizePlacedPhysicalPose(serverLevel, assembly);
    }

    public FrameSkin getPresentationSkin() {
        return presentationSkin;
    }

    public void setPresentationSkin(FrameSkin presentationSkin) {
        FrameSkin safe = java.util.Objects.requireNonNull(presentationSkin, "presentationSkin");
        if (this.presentationSkin == safe) return;
        this.presentationSkin = safe;
        markAndSynchronize();
    }

    public int getOccupiedMask() { return occupiedMask; }

    public void setOccupiedMask(int occupiedMask) {
        int sanitized = occupiedMask & 0xFF;
        if (this.occupiedMask == sanitized) return;
        this.occupiedMask = sanitized;
        markAndSynchronize();
    }

    /**
     * Applies a portable schematic payload only when this loaded NBT no longer describes the
     * assembly slot currently owning the destination position. A matching id+logical offset is the
     * normal chunk-load case and must never replay redundant snapshot data over Sable persistence.
     */
    public void processPortableRestore(ServerLevel serverLevel) {
        if (!portableRestorePending || portableMiniContent == null || isRemoved()) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly current = manager.getAssemblyAt(worldPosition).orElse(null);
        if (current != null
                && Objects.equals(assemblyId, current.id())
                && logicalFrameOffset.equals(current.logicalFrameOffset(worldPosition))) {
            portableRestorePending = false;
            portableRestoreAttempts = 0;
            return;
        }

        if (!MechanismAssemblyHost.canHostFrame(serverLevel, worldPosition)) {
            portableRestorePending = false;
            AntikytheraMechanism.LOGGER.error(
                    "Refusing portable schematic restore for Frame {} because its destination cannot host a Mechanism Frame",
                    worldPosition);
            return;
        }

        PortableFrameContent snapshot;
        try {
            snapshot = PortableFrameContent.load(portableMiniContent, serverLevel.registryAccess());
        } catch (RuntimeException exception) {
            portableRestorePending = false;
            AntikytheraMechanism.LOGGER.error(
                    "Discarding invalid portable mini-content payload for Frame {}",
                    worldPosition,
                    exception);
            return;
        }

        if (current == null) {
            current = manager.onFramePlaced(serverLevel, worldPosition);
        }
        if (current == null || !current.frames().contains(worldPosition)) {
            retryPortableRestore(serverLevel, "assembly mapping was not available");
            return;
        }

        manager.setFrameShellMode(serverLevel, worldPosition, snapshot.shellMode());
        manager.setFrameSkin(serverLevel, worldPosition, snapshot.skin());
        setAssemblyMapping(current.id(), current.orientation(), current.logicalFrameOffset(worldPosition));
        if (!snapshot.restore(serverLevel, current, worldPosition)) {
            retryPortableRestore(serverLevel, "mini-content materialization failed");
            return;
        }

        manager.refreshFrame(serverLevel, worldPosition);
        portableRestorePending = false;
        portableRestoreAttempts = 0;
        AntikytheraMechanism.LOGGER.debug(
                "Restored portable schematic mini content for Frame {} into assembly {}",
                worldPosition,
                current.id());
    }

    private void retryPortableRestore(ServerLevel serverLevel, String reason) {
        portableRestoreAttempts++;
        if (portableRestoreAttempts >= MAX_PORTABLE_RESTORE_ATTEMPTS) {
            portableRestorePending = false;
            AntikytheraMechanism.LOGGER.error(
                    "Portable schematic restore for Frame {} failed after {} attempts: {}",
                    worldPosition,
                    portableRestoreAttempts,
                    reason);
            return;
        }
        schedulePortableRestore(serverLevel);
    }

    private void schedulePortableRestore(ServerLevel serverLevel) {
        if (portableRestorePending
                && serverLevel.getBlockState(worldPosition).is(ModRegistries.MECHANISM_FRAME.get())) {
            serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    private void markAndSynchronize() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            schedulePortableRestore(serverLevel);
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
        presentationSkin = FrameSkin.fromSerializedName(tag.getString(PRESENTATION_SKIN_TAG));
        portableMiniContent = tag.contains(PortableFrameContent.FRAME_NBT_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PortableFrameContent.FRAME_NBT_TAG).copy()
                : null;
        portableRestorePending = portableMiniContent != null;
        portableRestoreAttempts = 0;
        if (level instanceof ServerLevel serverLevel) {
            schedulePortableRestore(serverLevel);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (assemblyId != null) tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        tag.putInt(OCCUPIED_MASK_TAG, occupiedMask);
        tag.put(ORIENTATION_TAG, orientation.save());
        tag.putLong(LOGICAL_OFFSET_TAG, logicalFrameOffset.asLong());
        tag.putString(PRESENTATION_SKIN_TAG, presentationSkin.serializedName());

        if (level instanceof ServerLevel serverLevel) {
            MechanismAssembly assembly = MechanismAssemblyManager.get(serverLevel)
                    .getAssemblyAt(worldPosition)
                    .orElse(null);
            if (assembly != null) {
                try {
                    portableMiniContent = PortableFrameContent.capture(serverLevel, assembly, worldPosition);
                } catch (RuntimeException exception) {
                    AntikytheraMechanism.LOGGER.error(
                            "Could not capture portable mini content for Frame {}; keeping the previous transport snapshot if available",
                            worldPosition,
                            exception);
                }
            }
        }
        if (portableMiniContent != null) {
            tag.put(PortableFrameContent.FRAME_NBT_TAG, portableMiniContent.copy());
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
