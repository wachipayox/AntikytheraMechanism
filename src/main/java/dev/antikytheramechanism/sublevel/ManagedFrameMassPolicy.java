package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

/**
 * Physical Sable mass policy for the Mechanism Frame shell itself.
 *
 * <p>Mini payload mass is no longer folded into each Frame. The managed child SubLevel already owns
 * the authoritative Sable mass/center-of-mass/inertia calculation for its contents, and
 * {@link HostedMiniMassBridge} projects that complete distribution into a foreign physical host.
 * Keeping the Frame at a stable shell mass also makes Sable assembly/disassembly independent from
 * transient Frame -> mini-cell mappings while {@code moveBlocks} is copying blocks.</p>
 */
public final class ManagedFrameMassPolicy {
    public static final double FRAME_SHELL_MASS = 0.1;

    private ManagedFrameMassPolicy() {
    }

    /** Called by the Sable mass-property hook whenever Sable weighs a Mechanism Frame. */
    public static double effectiveFrameMass(BlockGetter blockGetter, BlockPos framePosition) {
        return FRAME_SHELL_MASS;
    }

    /**
     * Compatibility entry point retained for the existing Sable relocation journal. Payload mass is
     * no longer position-dependent, so both source and destination simply freeze the stable shell.
     */
    public static double snapshotEffectiveFrameMass(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePosition) {
        return FRAME_SHELL_MASS;
    }
}
