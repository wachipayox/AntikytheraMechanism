package dev.antikytheramechanism.sublevel;

import com.mojang.serialization.Codec;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.sablescale.scale.SubLevelScale;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
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

import java.util.UUID;

public final class MechanismSubLevelService {
    private static final String OWNER_TAG = "antikytheramechanism";
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final SubLevelLoadingTicketType<UUID> ASSEMBLY_TICKET = SubLevelLoadingTicketType.create(
            AntikytheraMechanism.id("assembly"),
            Codec.STRING.xmap(UUID::fromString, UUID::toString));

    private MechanismSubLevelService() {
    }

    /** Forces static ticket-type registration during mod construction. */
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

    /**
     * Restores the identity fields needed while Sable is still loading the plot itself.
     *
     * <p>Sable normally restores these fields after {@code ServerLevelPlot.load}. Empty managed
     * plots need them earlier: plot loading rebuilds mass and native bounds, and Antikythera's
     * empty-bounds policy must already be able to identify the SubLevel at that point.</p>
     */
    public static boolean restoreOwnershipBeforePlotLoad(
            ServerSubLevel subLevel,
            CompoundTag serializedSubLevel) {
        if (!isSerializedManagedSubLevel(serializedSubLevel)) {
            return false;
        }

        subLevel.setName(serializedSubLevel.getString("display_name"));
        subLevel.setUserDataTag(serializedSubLevel.getCompound("user_data").copy());

        // Allocation built the initial MassTracker before the persisted identity was available.
        // Add the non-colliding structural mass now; plot.load() will merge it before notifying
        // Rapier of the final stats.
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

    public static ServerSubLevel create(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        Pose3d pose = new Pose3d();
        pose.position().set(
                assembly.origin().getX() + 0.5,
                assembly.origin().getY() + 0.5,
                assembly.origin().getZ() + 0.5);
        pose.scale().set(MiniCoordinateMapper.SUBLEVEL_SCALE);

        /*
         * Sable notifies its physics observers from inside allocateNewSubLevel(), before this method
         * can attach Antikythera's normal name/user-data ownership marker. Mark this synchronous
         * allocation so ServerSubLevel#buildMassTracker receives the structural mechanism mass
         * before Rapier registers the body.
         */
        ServerSubLevel subLevel = ManagedSubLevelMassPolicy.duringManagedCreation(
                () -> (ServerSubLevel) container.allocateNewSubLevel(pose));

        /*
         * Ownership must exist before the first empty plot chunk is created. LevelPlot#newEmptyChunk
         * can immediately recalculate empty bounds and invoke ServerSubLevel#onPlotBoundsChanged.
         * If the owner marker is installed afterwards, Sable sees an anonymous empty SubLevel and
         * is allowed to mark it removed before Antikythera can preserve it. That produces a
         * create/remove/recreate loop when empty assemblies participate in merges.
         */
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
        if (subLevel.isRemoved()) {
            AntikytheraMechanism.LOGGER.error(
                    "Managed Sable SubLevel {} for assembly {} was marked removed while creating its empty plot; discarding it instead of retrying in a loop",
                    subLevel.getUniqueId(),
                    assembly.id());
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            assembly.setSubLevelId(null);
            MechanismAssemblyManager.get(level).setDirty();
            return null;
        }

        subLevel.logicalPose().position().set(
                assembly.origin().getX() + 0.5,
                assembly.origin().getY() + 0.5,
                assembly.origin().getZ() + 0.5);
        subLevel.logicalPose().scale().set(MiniCoordinateMapper.SUBLEVEL_SCALE);

        // Empty managed SubLevels deliberately keep BoundingBox3i.EMPTY. The parent Frame is the
        // first-placement interaction surface; no invisible Sable broadphase is needed until a real
        // mini block exists.
        AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, assembly.poseTarget());
        subLevel.updateLastPose();
        container.addForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
        AntikytheraMechanism.LOGGER.info(
                "Created Sable SubLevel {} for assembly {} at scale {}",
                subLevel.getUniqueId(),
                assembly.id(),
                subLevel.logicalPose().scale());
        return subLevel;
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

    /**
     * Resolves only an already-existing managed SubLevel. Destructive operations must use this
     * method so a temporarily unavailable UUID cannot be replaced with an empty plot and mistaken
     * for the original payload.
     */
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

    public static ServerSubLevel getOrCreate(ServerLevel level, MechanismAssembly assembly) {
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
                        "Assembly {} still references unavailable SubLevel {}; refusing to replace possible persisted payload with an empty plot",
                        assembly.id(),
                        assembly.subLevelId());
                return null;
            }
            subLevel = create(level, assembly);
        }
        if (subLevel != null) {
            enforceScale(subLevel);
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                container.addForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
                AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, assembly.poseTarget());
            }
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

    /**
     * Ensures a mini coordinate and Sable's two-block expansion margin remain inside this plot,
     * and that its Y coordinate is valid for the parent dimension.
     */
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
        if (subLevel == null || container == null) {
            return;
        }

        container.removeForceLoadTicket(subLevel, ASSEMBLY_TICKET, assembly.id());
        container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
    }

    private static void enforceScale(ServerSubLevel subLevel) {
        if (Math.abs(subLevel.logicalPose().scale().x() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().y() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().z() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6) {
            SubLevelScale.apply(subLevel, MiniCoordinateMapper.SUBLEVEL_SCALE);
        }
    }
}
