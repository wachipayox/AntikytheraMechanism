package dev.antikytheramechanism.compat.sablephotomancy;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.SchematicAssemblyImportService;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.frame.PortableFrameContent;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEvent;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import dev.rew1nd.sableschematicapi.api.blueprint.SubLevelSaveFrame;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Native blueprint mapping for Sable Photomancy. Loaded only when its optional API mod is present. */
public final class SablePhotomancyIntegration {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String ASSEMBLIES_TAG = "assemblies";
    private static final String CHILD_SUBLEVEL_ID_TAG = "child_sublevel_id";
    private static final String ORIGIN_TAG = "origin";
    private static final String FRAMES_TAG = "frames";
    private static final String REF_SUBLEVEL_TAG = "sublevel";
    private static final String REF_POSITION_TAG = "position";
    private static final ResourceLocation EVENT_ID = AntikytheraMechanism.id("sable_photomancy_frames");

    private SablePhotomancyIntegration() {
    }

    public static void register() {
        SableBlueprintMapperRegistry.register(
                ModRegistries.MECHANISM_FRAME_BLOCK_ENTITY.get(),
                new FrameMapper());
        SableBlueprintEventRegistry.register(new FrameAssemblyEvent());
        AntikytheraMechanism.LOGGER.info("Enabled Sable Photomancy compatibility for managed Mechanism Frame SubLevels");
    }

    private static final class FrameMapper implements SableBlueprintBlockMapper {
        @Override
        public @Nullable CompoundTag save(
                BlueprintBlockSaveContext context,
                @Nullable CompoundTag defaultTag) {
            if (defaultTag == null
                    || !(context.blockEntity() instanceof MechanismFrameBlockEntity frame)
                    || frame.getAssemblyId() == null) {
                return defaultTag;
            }

            MechanismAssembly assembly = MechanismAssemblyManager.get(context.session().level())
                    .getAssembly(frame.getAssemblyId())
                    .orElse(null);
            if (assembly == null || !hasNativeManagedCopy(context.session(), assembly)) {
                // Partial/unsupported captures retain the generic eight-cell transport payload.
                return defaultTag;
            }

            CompoundTag mapped = defaultTag.copy();
            mapped.remove(PortableFrameContent.FRAME_NBT_TAG);
            mapped.remove(ASSEMBLY_ID_TAG);
            return mapped;
        }
    }

    private static final class FrameAssemblyEvent implements SableBlueprintEvent {
        @Override
        public ResourceLocation id() {
            return EVENT_ID;
        }

        @Override
        public void onSaveBeforeBlocks(BlueprintSaveSession session, CompoundTag data) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(session.level());
            int nextBlueprintId = session.frames().stream()
                    .mapToInt(SubLevelSaveFrame::blueprintId)
                    .max()
                    .orElse(-1) + 1;

            for (MechanismAssembly assembly : List.copyOf(manager.assemblies())) {
                if (!isFullyRepresentedBySelectedHost(session, assembly)) {
                    continue;
                }
                ServerSubLevel child = MechanismSubLevelService.findExisting(session.level(), assembly);
                if (child == null || session.blueprintId(child.getUniqueId()) != null) {
                    continue;
                }
                BoundingBox3ic bounds = child.getPlot().getBoundingBox();
                if (bounds == BoundingBox3i.EMPTY || bounds.volume() <= 0) {
                    continue;
                }

                BoundingBox3i storageBounds = new BoundingBox3i(bounds);
                BlockPos blocksOrigin = new BlockPos(
                        storageBounds.minX(), storageBounds.minY(), storageBounds.minZ());
                session.addFrame(new SubLevelSaveFrame(
                        nextBlueprintId++,
                        child.getUniqueId(),
                        child,
                        storageBounds,
                        blocksOrigin,
                        child.logicalPose()));
            }
        }

        @Override
        public void onSaveAfterBlocks(BlueprintSaveSession session, CompoundTag data) {
            ListTag records = new ListTag();
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(session.level());

            for (MechanismAssembly assembly : List.copyOf(manager.assemblies())) {
                ServerSubLevel child = MechanismSubLevelService.findExisting(session.level(), assembly);
                if (child == null
                        || !isFullyRepresentedBySelectedHost(session, assembly)
                        || session.blueprintId(child.getUniqueId()) == null) {
                    continue;
                }

                Optional<BlueprintBlockRef> originRef = session.blockRef(assembly.origin());
                if (originRef.isEmpty()) {
                    continue;
                }
                List<BlueprintBlockRef> frameRefs = new ArrayList<>(assembly.frames().size());
                boolean complete = true;
                for (BlockPos framePos : assembly.frames()) {
                    Optional<BlueprintBlockRef> ref = session.blockRef(framePos);
                    if (ref.isEmpty()) {
                        complete = false;
                        break;
                    }
                    frameRefs.add(ref.get());
                }
                if (!complete) {
                    continue;
                }

                CompoundTag record = new CompoundTag();
                record.putUUID(ASSEMBLY_ID_TAG, assembly.id());
                record.putUUID(CHILD_SUBLEVEL_ID_TAG, child.getUniqueId());
                record.put(ORIGIN_TAG, saveRef(originRef.get()));
                ListTag frameList = new ListTag();
                frameRefs.forEach(ref -> frameList.add(saveRef(ref)));
                record.put(FRAMES_TAG, frameList);
                records.add(record);
            }
            data.put(ASSEMBLIES_TAG, records);
        }

        @Override
        public void onPlaceAfterBlockEntities(BlueprintPlaceSession session, CompoundTag data) {
            if (!data.contains(ASSEMBLIES_TAG, Tag.TAG_LIST)) {
                return;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(session.level());
            if (container == null) {
                return;
            }

            ListTag records = data.getList(ASSEMBLIES_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < records.size(); index++) {
                CompoundTag record = records.getCompound(index);
                if (!record.hasUUID(ASSEMBLY_ID_TAG)
                        || !record.hasUUID(CHILD_SUBLEVEL_ID_TAG)
                        || !record.contains(ORIGIN_TAG, Tag.TAG_COMPOUND)
                        || !record.contains(FRAMES_TAG, Tag.TAG_LIST)) {
                    AntikytheraMechanism.LOGGER.warn("Skipping incomplete Antikythera Photomancy assembly record {}", index);
                    continue;
                }

                BlueprintBlockRef sourceOrigin = loadRef(record.getCompound(ORIGIN_TAG));
                BlockPos placedOrigin = sourceOrigin == null ? null : session.mapBlock(sourceOrigin);
                Set<BlockPos> placedFrames = new LinkedHashSet<>();
                ListTag frameTags = record.getList(FRAMES_TAG, Tag.TAG_COMPOUND);
                boolean complete = placedOrigin != null;
                for (int frameIndex = 0; frameIndex < frameTags.size(); frameIndex++) {
                    BlueprintBlockRef sourceFrame = loadRef(frameTags.getCompound(frameIndex));
                    BlockPos placedFrame = sourceFrame == null ? null : session.mapBlock(sourceFrame);
                    if (placedFrame == null) {
                        complete = false;
                        break;
                    }
                    placedFrames.add(placedFrame.immutable());
                }
                if (!complete || placedFrames.isEmpty() || !placedFrames.contains(placedOrigin)) {
                    AntikytheraMechanism.LOGGER.warn("Skipping Antikythera Photomancy assembly {} because its Frame mapping is incomplete", index);
                    continue;
                }

                UUID sourceAssemblyId = record.getUUID(ASSEMBLY_ID_TAG);
                UUID importedAssemblyId = session.allocateMappedUuid(sourceAssemblyId);
                UUID placedChildId = session.mapSubLevel(record.getUUID(CHILD_SUBLEVEL_ID_TAG));
                SubLevel placedChild = placedChildId == null ? null : container.getSubLevel(placedChildId);
                if (!(placedChild instanceof ServerSubLevel child) || child.isRemoved()) {
                    AntikytheraMechanism.LOGGER.warn(
                            "Skipping Antikythera Photomancy assembly {} because copied child SubLevel {} is unavailable",
                            sourceAssemblyId,
                            placedChildId);
                    continue;
                }

                MechanismAssembly imported = SchematicAssemblyImportService.registerCompleteAssembly(
                        session.level(),
                        importedAssemblyId,
                        placedOrigin,
                        placedFrames);
                if (imported == null) {
                    AntikytheraMechanism.LOGGER.warn(
                            "Could not register copied Antikythera assembly {} at {}",
                            sourceAssemblyId,
                            placedOrigin);
                    continue;
                }

                adoptCopiedManagedChild(session, imported, child);
                MechanismAssemblyManager manager = MechanismAssemblyManager.get(session.level());
                for (BlockPos framePos : placedFrames) {
                    manager.onFramePlaced(session.level(), framePos);
                    manager.refreshFrame(session.level(), framePos);
                }
            }
        }
    }

    private static boolean hasNativeManagedCopy(BlueprintSaveSession session, MechanismAssembly assembly) {
        if (!isFullyRepresentedBySelectedHost(session, assembly)) {
            return false;
        }
        ServerSubLevel child = MechanismSubLevelService.findExisting(session.level(), assembly);
        return child != null && session.blueprintId(child.getUniqueId()) != null;
    }

    private static boolean isFullyRepresentedBySelectedHost(
            BlueprintSaveSession session,
            MechanismAssembly assembly) {
        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(session.level(), assembly.origin());
        UUID hostId = host.foreignId();
        if (host.kind() != MechanismAssemblyHost.Kind.FOREIGN || hostId == null) {
            return false;
        }

        SubLevelSaveFrame hostFrame = session.frames().stream()
                .filter(frame -> frame.sourceUuid().equals(hostId))
                .findFirst()
                .orElse(null);
        return hostFrame != null && assembly.frames().stream().allMatch(hostFrame::contains);
    }

    private static void adoptCopiedManagedChild(
            BlueprintPlaceSession session,
            MechanismAssembly assembly,
            ServerSubLevel child) {
        CompoundTag owner = new CompoundTag();
        owner.putUUID(ASSEMBLY_ID_TAG, assembly.id());
        CompoundTag userData = new CompoundTag();
        userData.put("antikytheramechanism", owner);
        CompoundTag serializedEnvelope = new CompoundTag();
        serializedEnvelope.putString("display_name", "antikythera-" + assembly.id());
        serializedEnvelope.put("user_data", userData);

        if (!MechanismSubLevelService.restoreOwnershipBeforePlotLoad(child, serializedEnvelope)) {
            throw new IllegalStateException("Could not mark copied SubLevel as Antikythera-managed");
        }
        assembly.setSubLevelId(child.getUniqueId());
        MechanismAssemblyManager.get(session.level()).setDirty();
        ServerSubLevel prepared = MechanismSubLevelService.ensureForContent(session.level(), assembly);
        if (prepared != child) {
            throw new IllegalStateException(
                    "Copied Antikythera child SubLevel could not be adopted by assembly " + assembly.id());
        }
    }

    private static CompoundTag saveRef(BlueprintBlockRef ref) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(REF_SUBLEVEL_TAG, ref.subLevelId());
        tag.putLong(REF_POSITION_TAG, ref.localPos().asLong());
        return tag;
    }

    private static @Nullable BlueprintBlockRef loadRef(CompoundTag tag) {
        if (!tag.contains(REF_SUBLEVEL_TAG, Tag.TAG_INT)
                || !tag.contains(REF_POSITION_TAG, Tag.TAG_LONG)) {
            return null;
        }
        return new BlueprintBlockRef(tag.getInt(REF_SUBLEVEL_TAG), BlockPos.of(tag.getLong(REF_POSITION_TAG)));
    }
}
