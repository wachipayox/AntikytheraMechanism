package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Parent-world kinetic node shared by all four transmission box variants. */
public final class TransmissionBoxBlockEntity extends KineticBlockEntity {
    private static final String LINK_NONCE_TAG = "LinkNonce";
    private static final String ASSEMBLY_ID_TAG = "AssemblyId";
    private static final String COVER_MASK_TAG = "CoverMask";
    private static final String LINK_STATE_TAG = "LinkState";

    private UUID linkNonce = UUID.randomUUID();
    private UUID assemblyId;
    private int coverMask;
    private TransmissionLinkState linkState = TransmissionLinkState.UNBOUND;
    private final Map<Integer, BlockPos> activePeers = new HashMap<>();
    private int reconciliationCooldown;

    public TransmissionBoxBlockEntity(BlockPos pos, BlockState state) {
        super(CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get(), pos, state);
    }

    public TransmissionBoxKind kind() {
        return getBlockState().getBlock() instanceof TransmissionBoxBlock box
                ? box.kind()
                : TransmissionBoxKind.FOUR_SHAFTS;
    }

    public UUID linkNonce() {
        return linkNonce;
    }

    public UUID assemblyId() {
        return assemblyId;
    }

    public int coverMask() {
        return coverMask & 0xF;
    }

    public TransmissionLinkState linkState() {
        return linkState;
    }

    public boolean isActive() {
        return linkState == TransmissionLinkState.ACTIVE && !activePeers.isEmpty();
    }

    public Map<Integer, BlockPos> activePeers() {
        return Map.copyOf(activePeers);
    }

    public void bind(UUID owningAssembly) {
        Objects.requireNonNull(owningAssembly, "owningAssembly");
        if (!owningAssembly.equals(assemblyId)) {
            assemblyId = owningAssembly;
            linkNonce = UUID.randomUUID();
        }
        linkState = TransmissionLinkState.INSTALLING_LOCAL;
        activePeers.clear();
        setChanged();
        sendData();
    }

    public void setCoverMask(int mask) {
        int normalized = mask & 0xF;
        if (coverMask == normalized) {
            return;
        }
        coverMask = normalized;
        if (level != null && getBlockState().hasProperty(TransmissionBoxBlock.COVER_MASK)
                && getBlockState().getValue(TransmissionBoxBlock.COVER_MASK) != normalized) {
            level.setBlock(
                    worldPosition,
                    TransmissionBoxBlock.withCoverMask(getBlockState(), normalized),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        setChanged();
        sendData();
    }

    public void activate(UUID owningAssembly, Map<Integer, BlockPos> peers) {
        assemblyId = Objects.requireNonNull(owningAssembly, "owningAssembly");
        activePeers.clear();
        peers.forEach((index, position) -> activePeers.put(index, position.immutable()));
        linkState = activePeers.isEmpty() ? TransmissionLinkState.SUSPENDED : TransmissionLinkState.ACTIVE;
        setChanged();
        sendData();
    }

    public void suspend(TransmissionLinkState state) {
        if (state == TransmissionLinkState.ACTIVE || state == TransmissionLinkState.INSTALLING_LOCAL) {
            throw new IllegalArgumentException("Suspend requires a non-active state");
        }
        activePeers.clear();
        linkState = state;
        setChanged();
        sendData();
    }

    public boolean acceptsRemotePeer(int portIndex, BlockPos peerPos, UUID nonce, UUID owningAssembly) {
        return linkState == TransmissionLinkState.ACTIVE
                && linkNonce.equals(nonce)
                && Objects.equals(assemblyId, owningAssembly)
                && peerPos != null
                && peerPos.equals(activePeers.get(portIndex));
    }

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        if (linkState == TransmissionLinkState.ACTIVE && level != null) {
            for (BlockPos peer : activePeers.values()) {
                if (level.isLoaded(peer) && !neighbours.contains(peer)) {
                    neighbours.add(peer);
                }
            }
        }
        return neighbours;
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
        if (!(target instanceof InternalTransmissionPortBlockEntity proxy)) {
            return false;
        }
        BlockPos expected = activePeers.get(proxy.portIndex());
        return linkState == TransmissionLinkState.ACTIVE
                && expected != null
                && expected.equals(proxy.getBlockPos())
                && proxy.matches(linkNonce, assemblyId, worldPosition, proxy.portIndex())
                && proxy.isRemoteEnabled();
    }

    @Override
    public void initialize() {
        super.initialize();
        if (level instanceof ServerLevel) {
            if (getBlockState().hasProperty(TransmissionBoxBlock.COVER_MASK)
                    && getBlockState().getValue(TransmissionBoxBlock.COVER_MASK) != coverMask()) {
                level.setBlock(
                        worldPosition,
                        TransmissionBoxBlock.withCoverMask(getBlockState(), coverMask()),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            linkState = TransmissionLinkState.SUSPENDED;
            activePeers.clear();
            reconciliationCooldown = 1;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (--reconciliationCooldown <= 0) {
            reconciliationCooldown = 20;
            TransmissionLinkCoordinator.reconcile(serverLevel, worldPosition);
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putUUID(LINK_NONCE_TAG, linkNonce);
        if (assemblyId != null) {
            tag.putUUID(ASSEMBLY_ID_TAG, assemblyId);
        }
        tag.putInt(COVER_MASK_TAG, coverMask());
        tag.putString(LINK_STATE_TAG, linkState.name());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        linkNonce = tag.hasUUID(LINK_NONCE_TAG) ? tag.getUUID(LINK_NONCE_TAG) : UUID.randomUUID();
        assemblyId = tag.hasUUID(ASSEMBLY_ID_TAG) ? tag.getUUID(ASSEMBLY_ID_TAG) : null;
        coverMask = tag.contains(COVER_MASK_TAG, Tag.TAG_INT) ? tag.getInt(COVER_MASK_TAG) & 0xF : 0;
        // Never restore a live remote edge from disk or contraption NBT.
        linkState = assemblyId == null ? TransmissionLinkState.UNBOUND : TransmissionLinkState.SUSPENDED;
        activePeers.clear();
        reconciliationCooldown = 1;
    }
}
