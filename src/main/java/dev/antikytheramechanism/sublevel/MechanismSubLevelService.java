package dev.antikytheramechanism.sublevel;

import com.mojang.serialization.Codec;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.mixin.ChunkMapAccessor;
import dev.antikytheramechanism.mixin.ServerChunkCacheAccessor;
import dev.sablescale.scale.SubLevelScale;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

public final class MechanismSubLevelService {
    private static final String OWNER_TAG = "antikytheramechanism";
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final SubLevelLoadingTicketType<UUID> ASSEMBLY_TICKET = SubLevelLoadingTicketType.create(
            AntikytheraMechanism.id("assembly"),
            Codec.STRING.xmap(UUID::fromString, UUID::toString));

    private MechanismSubLevelService() {
    }

    public static void bootstrap() {
    }

    public static UUID getOwnerAssemblyId(ServerSubLevel subLevel) {
        CompoundTag userData = subLevel.getUserDataTag();
        if (userData == null || !userData.contains(OWNER_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag owner = userData.getCompound(OWNER_TAG);
        return owner.hasUUID(ASSEMBLY_ID_TAG) ? owner.getUUID(ASSEMBLY_ID_TAG) : null;
    }

    public static boolean restoreOwnershipBeforePlotLoad(
            ServerSubLevel subLevel,
            CompoundTag serializedSubLevel) {
        if (!isSerializedManagedSubLevel(serializedSubLevel)) {
            return false;
        }

        subLevel.setName(serializedSubLevel.getString("display_name"));
        subLevel.setUserDataTag(serializedSubLevel.getCompound("user_data").copy());
        ManagedSubLevelMassPolicy.applyStructuralMass(subLevel);
        return true;
    }

    static boolean isSerializedManagedSubLevel(CompoundTag serializedSubLevel) {
        if (serializedSubLevel == null
                || !serializedSubLevel.contains("display_name", Tag.TAG_STRING)
                || !serializedSubLevel.getString("display_name").startsWith("antikythera-")
                || !serializedSubLevel.contains("user_data", Tag.TAG_COMPOUND)) {
            return false;
        }

        CompoundTag userData = serializedSubLevel.getCompound("user_data");
        if (!userData.contains(OWNER_TAG, Tag.TAG_COMPOUND)) {
            return false;
        }
        return userData.getCompound(OWNER_TAG).hasUUID(ASSEMBLY_ID_TAG);
    }

    /**
     * Ensures a Sable SubLevel because the caller is about to create or receive real physical mini
     * content. Empty Frame graphs must not call this method merely to exist, move, merge or tick.
     */
    public static ServerSubLevel ensureForContent(ServerLevel level, MechanismAssembly assembly) {
        AssemblyPose worldTarget = MechanismAssemblyHost.worldPose(level, assembly);
        if (worldTarget == null) {
            AntikytheraMechanism.LOGGER.warn(
                    "Cannot materialize mini content for assembly {} because its physical host is unavailable or unsupported",
                    assembly.id());
            return null;
        }

        ServerSubLevel subLevel = findExisting(level, assembly);
        if (subLevel == null) {
            if (hasOwnedSubLevel(level, assembly.id())) {
                AntikytheraMechanism.LOGGER.error(
                        "Refusing to create another SubLevel for ambiguous assembly {}",
                        assembly.id());
                return null;
            }
            if (assembly.subLevelId() != null) {
                AntikytheraMechanism.LOGGER.error(
                        "Assembly {} still references unavailable SubLevel {}; refusing to replace possible persisted payload",
                        assembly.id(),
                        assembly.subLevelId());
                return null;
            }
            subLevel = createForContent(level, assembly, worldTarget);
        }
        return prepareExisting(level, assembly, subLevel, worldTarget);
    }

    private static ServerSubLevel createForContent(
            ServerLevel level,
            MechanismAssembly assembly,
            AssemblyPose worldTarget) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        Pose3d pose = new Pose3d();
        pose.position().set(worldTarget.anchor(new Vector3d()));
        pose.orientation().set(worldTarget.orientation(new Quaterniond()));
        pose.scale().set(MiniCoordinateMapper.SUBLEVEL_SCALE);

        ServerSubLevel subLevel = ManagedSubLevelMassPolicy.duringManagedCreation(
                () -> (ServerSubLevel) container.allocateNewSubLevel(pose));

        CompoundTag owner = new CompoundTag();
        owner.putUUID(ASSEMBLY_ID_TAG, assembly.id());
        CompoundTag userData = new CompoundTag();
        userData.put(OWNER_TAG, owner);
        subLevel.setUserDataTag(userData);
        subLevel.setName("antikythera-" + assembly.id());
        assembly.setSubLevelId(subLevel.getUniqueId());
        MechanismAssemblyManager.get(level).setDirty();

        LevelPlot plot = subLevel.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        publishStagedPlotChunks(level);
        if (subLevel.isRemoved()) {
            AntikytheraMechanism.LOGGER.error(
                    "Managed Sable SubLevel {} for assembly {} was removed during content staging",
                    subLevel.getUniqueId(),
                    assembly.id());
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            assembly.setSubLevelId(null);
            MechanismAssemblyManager.get(level).setDirty();
            return null;
        }

        subLevel.logicalPose().scale().set(MiniCoordinateMapper.SUBLEVEL_SCALE);
        AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, worldTarget);
        subLevel.updateLastPose();
        container.addForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
        AntikytheraMechanism.LOGGER.debug(
                "Staged Sable SubLevel {} for content in assembly {}",
                subLevel.getUniqueId(),
                assembly.id());
        return subLevel;
    }

    /**
     * Sable stages a freshly allocated plot holder in ChunkMap's updating map. Antikythera creates
     * mini content synchronously in the same server interaction, so publish that holder before any
     * vanilla ServerLevel read/write can resolve the old terrain chunk occupying the plot address.
     */
    private static void publishStagedPlotChunks(ServerLevel level) {
        ServerChunkCacheAccessor chunkSource = (ServerChunkCacheAccessor) (Object) level.getChunkSource();
        ChunkMapAccessor chunkMap = (ChunkMapAccessor) (Object) chunkSource.antikytheramechanism$getChunkMap();
        chunkMap.antikytheramechanism$promoteChunkMap();
    }

    public static ServerSubLevel get(ServerLevel level, MechanismAssembly assembly) {
        if (assembly.subLevelId() == null) {
            return null;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel subLevel = container.getSubLevel(assembly.subLevelId());
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)
                || serverSubLevel.isRemoved()
                || !assembly.id().equals(getOwnerAssemblyId(serverSubLevel))) {
            return null;
        }
        return serverSubLevel;
    }

    public static ServerSubLevel findExisting(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        ServerSubLevel direct = get(level, assembly);
        ServerSubLevel match = direct;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ServerSubLevel serverCandidate)
                    || serverCandidate.isRemoved()
                    || !assembly.id().equals(getOwnerAssemblyId(serverCandidate))) {
                continue;
            }
            if (serverCandidate == match) {
                continue;
            }
            if (match != null && match != serverCandidate) {
                AntikytheraMechanism.LOGGER.error(
                        "Assembly {} has multiple owned SubLevels ({} and {}); refusing an ambiguous recovery",
                        assembly.id(),
                        match.getUniqueId(),
                        serverCandidate.getUniqueId());
                return null;
            }
            match = serverCandidate;
        }
        if (match != null && !match.getUniqueId().equals(assembly.subLevelId())) {
            assembly.setSubLevelId(match.getUniqueId());
            MechanismAssemblyManager.get(level).setDirty();
            AntikytheraMechanism.LOGGER.warn(
                    "Recovered existing SubLevel {} for assembly {} by its ownership marker",
                    match.getUniqueId(),
                    assembly.id());
        }
        return match;
    }

    public static boolean isPhysicallyEmpty(ServerSubLevel subLevel) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        return bounds == null
                || bounds.minX() > bounds.maxX()
                || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ()
                || bounds.volume() <= 0.0;
    }

    public static boolean retireIfEmpty(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevel subLevel = findExisting(level, assembly);
        if (subLevel == null || !isPhysicallyEmpty(subLevel)) {
            return false;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }

        UUID retiredId = subLevel.getUniqueId();
        container.removeForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
        container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        if (retiredId.equals(assembly.subLevelId())) {
            assembly.setSubLevelId(null);
            manager.setDirty();
        }
        for (BlockPos frame : assembly.frames()) {
            if (level.hasChunkAt(frame)) {
                manager.refreshFrame(level, frame);
            }
        }

        AntikytheraMechanism.LOGGER.debug(
                "Retired empty Sable SubLevel {} while keeping assembly {}",
                retiredId,
                assembly.id());
        return true;
    }

    private static ServerSubLevel prepareExisting(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel subLevel,
            AssemblyPose worldTarget) {
        if (subLevel == null) {
            return null;
        }
        enforceScale(subLevel);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            container.addForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
            AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, worldTarget);
        }
        return subLevel;
    }

    private static boolean hasOwnedSubLevel(ServerLevel level, UUID assemblyId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (candidate instanceof ServerSubLevel serverCandidate
                    && !serverCandidate.isRemoved()
                    && assemblyId.equals(getOwnerAssemblyId(serverCandidate))) {
                return true;
            }
        }
        return false;
    }

    public static BlockPos toPlotPosition(ServerSubLevel subLevel, BlockPos miniPosition) {
        return subLevel.getPlot().getCenterBlock().offset(miniPosition);
    }

    public static boolean canAddressMiniPosition(
            ServerLevel level,
            ServerSubLevel subLevel,
            BlockPos miniPosition) {
        LevelPlot plot = subLevel.getPlot();
        BlockPos global = toPlotPosition(subLevel, miniPosition);
        if (global.getY() < level.getMinBuildHeight() || global.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        if (!plot.contains(new ChunkPos(global))) {
            return false;
        }
        for (net.minecraft.core.Direction direction : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.EAST}) {
            if (!plot.contains(new ChunkPos(global.relative(direction, 2)))) {
                return false;
            }
        }
        return true;
    }

    public static boolean canAddressFrame(
            ServerLevel level,
            ServerSubLevel subLevel,
            MechanismAssembly assembly,
            BlockPos framePosition) {
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    if (!canAddressMiniPosition(
                            level,
                            subLevel,
                            MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static void remove(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevel subLevel = findExisting(level, assembly);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (subLevel != null && container != null) {
            container.removeForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
        if (assembly.subLevelId() != null) {
            assembly.setSubLevelId(null);
            MechanismAssemblyManager.get(level).setDirty();
        }
    }

    private static void enforceScale(ServerSubLevel subLevel) {
        if (Math.abs(subLevel.logicalPose().scale().x() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().y() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().z() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6) {
            SubLevelScale.apply(subLevel, MiniCoordinateMapper.SUBLEVEL_SCALE);
        }
    }
}
