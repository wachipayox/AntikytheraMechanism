package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Kinetic endpoint installed in an assembly service-shell cell.
 *
 * <p>The remote edge is deliberately transient. A persisted proxy cannot reconnect until the
 * coordinator has revalidated its nonce, owning box and assembly.</p>
 */
public final class InternalTransmissionPortBlockEntity extends SimpleKineticBlockEntity {
    private static final String OWNER_NONCE_TAG = "OwnerNonce";
    private static final String ASSEMBLY_ID_TAG = "AssemblyId";
    private static final String PARENT_BOX_POS_TAG = "ParentBoxPos";
    private static final String PORT_INDEX_TAG = "PortIndex";

    private UUID ownerNonce;
    private UUID assemblyId;
    private BlockPos parentBoxPos;
    private int portIndex = -1;
    private boolean remoteEnabled;

    public InternalTransmissionPortBlockEntity(BlockPos pos, BlockState state) {
        super(CreateTransmissionRegistries.INTERNAL_TRANSMISSION_PORT_BLOCK_ENTITY.get(), pos, state);
    }

    public void configure(UUID nonce, UUID owningAssembly, BlockPos boxPos, int index) {
        ownerNonce = Objects.requireNonNull(nonce, "nonce");
        assemblyId = Objects.requireNonNull(owningAssembly, "owningAssembly");
        parentBoxPos = Objects.requireNonNull(boxPos, "boxPos").immutable();
        portIndex = index;
        remoteEnabled = false;
        setChanged();
        sendData();
    }

    public boolean matches(UUID nonce, UUID owningAssembly, BlockPos boxPos, int index) {
        return nonce != null
                && nonce.equals(ownerNonce)
                && owningAssembly != null
                && owningAssembly.equals(assemblyId)
                && boxPos != null
                && boxPos.equals(parentBoxPos)
                && index == portIndex;
    }

    public void setRemoteEnabled(boolean enabled) {
        if (remoteEnabled == enabled) {
            return;
        }
        remoteEnabled = enabled;
        setChanged();
    }

    public boolean isRemoteEnabled() {
        return remoteEnabled;
    }

    public UUID ownerNonce() {
        return ownerNonce;
    }

    public UUID assemblyId() {
        return assemblyId;
    }

    public BlockPos parentBoxPos() {
        return parentBoxPos;
    }

    public int portIndex() {
        return portIndex;
    }

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        List<BlockPos> expanded = super.addPropagationLocations(block, state, neighbours);
        if (level != null) {
            // Service ports are implementation nodes, not a second set of physical gears. Only
            // their intended mini target may connect through native Create geometry.
            expanded.removeIf(candidate -> {
                if (candidate.equals(parentBoxPos)) {
                    return false;
                }
                BlockEntity blockEntity = level.getBlockEntity(candidate);
                return blockEntity instanceof InternalTransmissionPortBlockEntity;
            });
        }
        if (remoteEnabled && parentBoxPos != null && level != null && level.isLoaded(parentBoxPos)
                && !expanded.contains(parentBoxPos)) {
            expanded.add(parentBoxPos);
        }
        return expanded;
    }

    @Override
    public float propagateRotationTo(
            KineticBlockEntity target,
            BlockState stateFrom,
            BlockState stateTo,
            BlockPos diff,
            boolean connectedViaAxes,
            boolean connectedViaCogs) {
        return isValidatedRemotePeer(target) ? 1.0F : 0.0F;
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return isValidatedRemotePeer(other);
    }

    private boolean isValidatedRemotePeer(KineticBlockEntity target) {
        return remoteEnabled
                && parentBoxPos != null
                && parentBoxPos.equals(target.getBlockPos())
                && target instanceof TransmissionBoxBlockEntity box
                && box.acceptsRemotePeer(portIndex, worldPosition, ownerNonce, assemblyId);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (ownerNonce != null) {
            tag.putUUID(OWNER_NONCE_TAG, ownerNonce);
        }
        if (assemblyId != null) {
            tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        }
        if (parentBoxPos != null) {
            tag.putLong(PARENT_BOX_POS_TAG, parentBoxPos.asLong());
        }
        tag.putInt(PORT_INDEX_TAG, portIndex);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        ownerNonce = tag.hasUUID(OWNER_NONCE_TAG) ? tag.getUUID(OWNER_NONCE_TAG) : null;
        assemblyId = tag.hasUUID(ASSEMBLY_ID_TAG) ? tag.getUUID(ASSEMBLY_ID_TAG) : null;
        parentBoxPos = tag.contains(PARENT_BOX_POS_TAG, Tag.TAG_LONG)
                ? BlockPos.of(tag.getLong(PARENT_BOX_POS_TAG))
                : null;
        portIndex = tag.contains(PORT_INDEX_TAG, Tag.TAG_INT) ? tag.getInt(PORT_INDEX_TAG) : -1;
        remoteEnabled = false;
    }
}
