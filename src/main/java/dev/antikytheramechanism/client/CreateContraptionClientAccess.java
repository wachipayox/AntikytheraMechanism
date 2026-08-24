package dev.antikytheramechanism.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class CreateContraptionClientAccess {
    private CreateContraptionClientAccess() {
    }

    public interface EntityCarrier {
        Object getAntikytheraContraption();
    }

    /** Client-only access implemented by Create controlled contraption entities. */
    public interface ControllerCarrier {
        @Nullable BlockPos getAntikytheraControllerPos();
    }

    public interface BlockCarrier {
        Map<BlockPos, StructureBlockInfo> getAntikytheraBlocks();
    }
}
