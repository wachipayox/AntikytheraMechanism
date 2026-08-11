package dev.antikytheramechanism.sublevel;

import com.mojang.serialization.Codec;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
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

        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        LevelPlot plot = subLevel.getPlot();
        ChunkPos centerChunk = plot.getCenterChunk();
        plot.newEmptyChunk(centerChunk);

        subLevel.logicalPose().position().set(
                assembly.origin().getX() + 0.5,
                assembly.origin().getY() + 0.5,
                assembly.origin().getZ() + 0.5);
        subLevel.logicalPose().scale().set(MiniCoordinateMapper.SUBLEVEL_SCALE);

        CompoundTag owner = new CompoundTag();
        owner.putUUID(ASSEMBLY_ID_TAG, assembly.id());
        CompoundTag userData = new CompoundTag();
        userData.put(OWNER_TAG, owner);
        subLevel.setUserDataTag(userData);
        subLevel.setName("antikythera-" + assembly.id());

        BlockPos safeAnchor = chooseSafeServiceAnchor(assembly);
        assembly.setServiceAnchor(safeAnchor);
        BlockPos anchorGlobal = toPlotPosition(subLevel, safeAnchor);
        boolean anchorAddressable = canAddressMiniPosition(level, subLevel, safeAnchor)
                && level.hasChunkAt(anchorGlobal);
        boolean anchorPlaced = anchorAddressable && FrameMaskWriteGuard.getBypassing(() ->
                level.setBlock(
                        anchorGlobal,
                        ModRegistries.ASSEMBLY_ANCHOR.get().defaultBlockState(),
                        3));
        if ((!anchorPlaced && !level.getBlockState(anchorGlobal)
                        .is(ModRegistries.ASSEMBLY_ANCHOR.get()))
                || subLevel.isRemoved()) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not create service anchor for assembly {}; discarding the unusable empty SubLevel {}",
                    assembly.id(),
                    subLevel.getUniqueId());
            if (!subLevel.isRemoved()) {
                container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            }
            return null;
        }

        AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, assembly.poseTarget());
        subLevel.updateLastPose();
        assembly.setSubLevelId(subLevel.getUniqueId());
        MechanismAssemblyManager.get(level).setDirty();
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
            if (!enforceScaleAndAnchor(level, assembly, subLevel)) {
                return null;
            }
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

    private static boolean enforceScaleAndAnchor(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel subLevel) {
        if (Math.abs(subLevel.logicalPose().scale().x() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().y() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6
                || Math.abs(subLevel.logicalPose().scale().z() - MiniCoordinateMapper.SUBLEVEL_SCALE) > 1.0E-6) {
            SubLevelScale.apply(subLevel, MiniCoordinateMapper.SUBLEVEL_SCALE);
        }

        return ensureServiceAnchorSafe(level, assembly, subLevel);
    }

    /**
     * Moves legacy anchors to a deterministic position two cells below the assembly's lower mini
     * corner. The destination is committed before the old anchor is removed and neither a foreign
     * block nor an unloaded chunk is touched.
     */
    public static boolean ensureServiceAnchorSafe(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel subLevel) {
        BlockPos previousAnchor = assembly.serviceAnchor();
        BlockPos safeAnchor = chooseSafeServiceAnchor(assembly);
        if (!canAddressMiniPosition(level, subLevel, safeAnchor)
                || ServiceShellReservations.find(level, assembly.id(), safeAnchor) != null) {
            AntikytheraMechanism.LOGGER.error(
                    "Cannot place the service anchor for assembly {} at safe local position {}",
                    assembly.id(),
                    safeAnchor);
            return false;
        }

        BlockPos safeGlobal = toPlotPosition(subLevel, safeAnchor);
        if (!level.hasChunkAt(safeGlobal)) {
            return false;
        }
        BlockPos previousGlobal = toPlotPosition(subLevel, previousAnchor);
        if (!previousAnchor.equals(safeAnchor) && !level.hasChunkAt(previousGlobal)) {
            return false;
        }

        net.minecraft.world.level.block.state.BlockState safeState = level.getBlockState(safeGlobal);
        if (!safeState.isAir() && !safeState.is(ModRegistries.ASSEMBLY_ANCHOR.get())) {
            AntikytheraMechanism.LOGGER.error(
                    "Refused to overwrite foreign service-shell content at {} while migrating assembly {} anchor",
                    safeAnchor,
                    assembly.id());
            return false;
        }
        net.minecraft.world.level.block.state.BlockState previousState = level.getBlockState(previousGlobal);
        if (!previousAnchor.equals(safeAnchor)
                && !previousState.isAir()
                && !previousState.is(ModRegistries.ASSEMBLY_ANCHOR.get())) {
            AntikytheraMechanism.LOGGER.error(
                    "Assembly {} anchor metadata points at foreign content {}; refusing migration",
                    assembly.id(),
                    previousAnchor);
            return false;
        }

        boolean placedNow = safeState.isAir();
        if (placedNow) {
            boolean placed = FrameMaskWriteGuard.getBypassing(() -> level.setBlock(
                    safeGlobal,
                    ModRegistries.ASSEMBLY_ANCHOR.get().defaultBlockState(),
                    3));
            if ((!placed && !level.getBlockState(safeGlobal).is(ModRegistries.ASSEMBLY_ANCHOR.get()))
                    || level.getBlockEntity(safeGlobal) != null) {
                return false;
            }
        }

        if (!previousAnchor.equals(safeAnchor) && previousState.is(ModRegistries.ASSEMBLY_ANCHOR.get())) {
            boolean removed = FrameMaskWriteGuard.getBypassing(() -> level.setBlock(
                    previousGlobal,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
                            | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS));
            if ((!removed && !level.getBlockState(previousGlobal).isAir())
                    || level.getBlockEntity(previousGlobal) != null) {
                if (placedNow) {
                    boolean reverted = FrameMaskWriteGuard.getBypassing(() -> level.setBlock(
                            safeGlobal,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                                    | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
                                    | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS));
                    if ((!reverted && !level.getBlockState(safeGlobal).isAir())
                            || level.getBlockEntity(safeGlobal) != null) {
                        AntikytheraMechanism.LOGGER.error(
                                "CRITICAL: service-anchor migration rollback failed for assembly {}",
                                assembly.id());
                    }
                }
                return false;
            }
        }

        if (!level.getBlockState(safeGlobal).is(ModRegistries.ASSEMBLY_ANCHOR.get())
                || level.getBlockEntity(safeGlobal) != null) {
            return false;
        }
        if (!previousAnchor.equals(safeAnchor)) {
            assembly.setServiceAnchor(safeAnchor);
            MechanismAssemblyManager.get(level).setDirty();
            AntikytheraMechanism.LOGGER.warn(
                    "Migrated service anchor for mechanism assembly {} to safe local position {} in {}",
                    assembly.id(),
                    safeAnchor,
                    level.dimension().location());
        }
        return true;
    }

    /** Pure deterministic allocator used by tests and service-shell preflight. */
    public static BlockPos chooseSafeServiceAnchor(MechanismAssembly assembly) {
        int minimumX = 0;
        int minimumY = 0;
        int minimumZ = 0;
        boolean first = true;
        for (BlockPos frame : assembly.frames()) {
            BlockPos offset = frame.subtract(assembly.origin());
            int miniX = offset.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            int miniY = offset.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            int miniZ = offset.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
            if (first) {
                minimumX = miniX;
                minimumY = miniY;
                minimumZ = miniZ;
                first = false;
            } else {
                minimumX = Math.min(minimumX, miniX);
                minimumY = Math.min(minimumY, miniY);
                minimumZ = Math.min(minimumZ, miniZ);
            }
        }
        return new BlockPos(
                minimumX,
                minimumY - 2,
                minimumZ);
    }

}
