package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Shared identity rule for implicit attachment between physical Mechanism Frames. */
public final class FrameAssemblyAttachment {
    private FrameAssemblyAttachment() {
    }

    /**
     * Returns true only when both physical Frame positions are indexed by the same logical assembly.
     *
     * <p>Geometric proximity is deliberately insufficient: separate Frame assemblies may touch each
     * other without becoming one movement structure. Create/Simulated glue remains responsible for
     * explicitly joining otherwise independent assemblies.</p>
     */
    public static boolean sameAssembly(Level level, BlockPos first, BlockPos second) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly firstAssembly = manager.getAssemblyAt(first).orElse(null);
        MechanismAssembly secondAssembly = manager.getAssemblyAt(second).orElse(null);
        return firstAssembly != null
                && secondAssembly != null
                && firstAssembly.id().equals(secondAssembly.id());
    }
}
