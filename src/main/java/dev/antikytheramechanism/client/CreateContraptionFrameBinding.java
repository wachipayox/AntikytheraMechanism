package dev.antikytheramechanism.client;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Decodes the stable Antikythera Frame binding stored inside a client-side Create contraption. */
public final class CreateContraptionFrameBinding {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";
    private static final String ORIENTATION_TAG = "frame_orientation";
    private static final String LOGICAL_OFFSET_TAG = "logical_frame_offset";

    private CreateContraptionFrameBinding() {
    }

    public static @Nullable Binding find(
            Map<BlockPos, StructureBlockInfo> blocks,
            UUID assemblyId) {
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            Binding binding = decode(entry);
            if (binding != null && assemblyId.equals(binding.assemblyId())) {
                return binding;
            }
        }
        return null;
    }

    public static Map<UUID, Binding> findAll(Map<BlockPos, StructureBlockInfo> blocks) {
        Map<UUID, Binding> result = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            Binding binding = decode(entry);
            if (binding != null) {
                result.putIfAbsent(binding.assemblyId(), binding);
            }
        }
        return result;
    }

    private static @Nullable Binding decode(Map.Entry<BlockPos, StructureBlockInfo> entry) {
        StructureBlockInfo info = entry.getValue();
        CompoundTag nbt = info.nbt();
        if (!info.state().is(ModRegistries.MECHANISM_FRAME.get())
                || nbt == null
                || !nbt.hasUUID(ASSEMBLY_ID_TAG)) {
            return null;
        }

        UUID assemblyId = nbt.getUUID(ASSEMBLY_ID_TAG);
        FrameOrientation orientation = nbt.contains(ORIENTATION_TAG)
                ? FrameOrientation.load(nbt.getCompound(ORIENTATION_TAG))
                : FrameOrientation.IDENTITY;
        BlockPos logicalOffset = nbt.contains(LOGICAL_OFFSET_TAG)
                ? BlockPos.of(nbt.getLong(LOGICAL_OFFSET_TAG))
                : BlockPos.ZERO;
        BlockPos localOrigin = entry.getKey().subtract(orientation.toPhysical(logicalOffset));
        return new Binding(assemblyId, localOrigin.immutable(), orientation);
    }

    public record Binding(UUID assemblyId, BlockPos localOrigin, FrameOrientation orientation) {
    }
}
