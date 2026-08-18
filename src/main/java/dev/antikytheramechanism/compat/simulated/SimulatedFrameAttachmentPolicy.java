package dev.antikytheramechanism.compat.simulated;

import dev.antikytheramechanism.assembly.FrameAssemblyAttachment;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Position-aware Frame attachment policy used only by Simulated's assembly search. */
public final class SimulatedFrameAttachmentPolicy {
    private SimulatedFrameAttachmentPolicy() {
    }

    /**
     * Overrides Simulated's positional attachment query only for Frame-to-Frame pairs.
     *
     * @param position the candidate Frame queried by Simulated
     * @param direction vector from {@code position} back toward the current BFS source block
     * @return true/false for a Frame pair, or null when Simulated should keep its normal rule
     */
    public static @Nullable Boolean attachmentOverride(
            BlockState state,
            Level level,
            BlockPos position,
            BlockPos direction) {
        if (!state.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        BlockPos sourcePosition = position.offset(direction);
        if (!level.getBlockState(sourcePosition).is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        return FrameAssemblyAttachment.sameAssembly(level, sourcePosition, position);
    }

    /**
     * Simulated separately evaluates explicit glue after its generic NeoForge stickiness check.
     * Disable only generic Frame-to-Frame stickiness so the positional assembly identity rule above
     * is authoritative while glue remains free to attach two independent assemblies.
     */
    public static boolean useGenericStickiness(BlockState first, BlockState second) {
        return !first.is(ModRegistries.MECHANISM_FRAME.get())
                || !second.is(ModRegistries.MECHANISM_FRAME.get());
    }
}
