package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Typed Create-side inspection of captured frame metadata. */
final class CreateFrameCapture {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";

    private CreateFrameCapture() {
    }

    static Optional<UUID> assemblyId(CompoundTag blockEntityData) {
        return blockEntityData != null && blockEntityData.hasUUID(ASSEMBLY_ID_TAG)
                ? Optional.of(blockEntityData.getUUID(ASSEMBLY_ID_TAG))
                : Optional.empty();
    }

    static Optional<Capture> inspect(Contraption contraption, Block frameBlock, UUID assemblyId) {
        Set<BlockPos> positions = new HashSet<>();
        for (StructureBlockInfo info : contraption.getBlocks().values()) {
            if (!info.state().is(frameBlock)) {
                continue;
            }
            Optional<UUID> capturedId = assemblyId(info.nbt());
            if (capturedId.isPresent() && capturedId.get().equals(assemblyId)) {
                positions.add(info.pos().immutable());
            }
        }
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        BlockPos leader = positions.stream().min(ContraptionPoseBinding.POSITION_ORDER).orElseThrow();
        return Optional.of(new Capture(assemblyId, Collections.unmodifiableSet(positions), leader));
    }

    static Captures inspectAll(Contraption contraption, Block frameBlock) {
        Map<UUID, Set<BlockPos>> mutable = new HashMap<>();
        boolean missingAssemblyId = false;
        for (StructureBlockInfo info : contraption.getBlocks().values()) {
            if (!info.state().is(frameBlock)) {
                continue;
            }
            Optional<UUID> id = assemblyId(info.nbt());
            if (id.isEmpty()) {
                missingAssemblyId = true;
                continue;
            }
            mutable.computeIfAbsent(id.get(), ignored -> new HashSet<>()).add(info.pos().immutable());
        }
        Map<UUID, Set<BlockPos>> captures = new HashMap<>();
        mutable.forEach((id, positions) -> captures.put(id, Collections.unmodifiableSet(positions)));
        return new Captures(Collections.unmodifiableMap(captures), missingAssemblyId);
    }

    record Capture(UUID assemblyId, Set<BlockPos> localFrames, BlockPos leaderLocalPosition) {
    }

    record Captures(Map<UUID, Set<BlockPos>> localFramesByAssembly, boolean missingAssemblyId) {
        boolean isEmpty() {
            return localFramesByAssembly.isEmpty() && !missingAssemblyId;
        }
    }
}
