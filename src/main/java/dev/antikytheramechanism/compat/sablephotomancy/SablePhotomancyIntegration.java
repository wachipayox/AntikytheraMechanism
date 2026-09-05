package dev.antikytheramechanism.compat.sablephotomancy;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal Sable Photomancy compatibility.
 *
 * <p>Managed Antikythera child SubLevels are deliberately not transported by Photomancy. A Frame
 * is copied as an ordinary block entity inside its physical host and carries the same portable
 * eight-cell payload used by vanilla structure copies. On placement the Frame restore path creates
 * and owns a fresh managed child SubLevel at the destination.</p>
 */
public final class SablePhotomancyIntegration {
    private static final String ASSEMBLY_ID_TAG = "assembly_id";

    private SablePhotomancyIntegration() {
    }

    public static void register() {
        SableBlueprintMapperRegistry.register(
                ModRegistries.MECHANISM_FRAME_BLOCK_ENTITY.get(),
                new FrameMapper());
        AntikytheraMechanism.LOGGER.info(
                "Enabled Sable Photomancy Frame payload compatibility; managed mini SubLevels are export-filtered");
    }

    private static final class FrameMapper implements SableBlueprintBlockMapper {
        @Override
        public @Nullable CompoundTag save(
                BlueprintBlockSaveContext context,
                @Nullable CompoundTag defaultTag) {
            if (defaultTag == null) {
                return null;
            }

            // Keep the portable mini-content snapshot exactly as vanilla structure-style copies do,
            // but never carry an authoritative assembly UUID into a newly created Sable host.
            CompoundTag mapped = defaultTag.copy();
            mapped.remove(ASSEMBLY_ID_TAG);
            return mapped;
        }
    }
}
