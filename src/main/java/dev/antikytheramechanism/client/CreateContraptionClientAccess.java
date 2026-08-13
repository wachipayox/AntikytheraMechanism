package dev.antikytheramechanism.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import java.util.Map;

public final class CreateContraptionClientAccess {
    private CreateContraptionClientAccess() {
    }

    public interface EntityCarrier {
        Object getAntikytheraContraption();
    }

    public interface BlockCarrier {
        Map<BlockPos, StructureBlockInfo> getAntikytheraBlocks();
    }
}
