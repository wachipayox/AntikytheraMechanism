package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Supplier;

/**
 * Supplies a tiny non-colliding structural mass to Antikythera SubLevels.
 *
 * <p>Sable deliberately assigns zero mass to non-solid blocks and to empty plots. Rapier, however,
 * requires every registered rigid body to have a valid center of mass. Mechanism assemblies are
 * anchored and pose-driven by their parent Frames, so their Sable body needs only a numerically
 * valid inertial frame; it does not need a hidden physical block.</p>
 */
public final class ManagedSubLevelMassPolicy {
    private static final double STRUCTURAL_MASS = 1.0E-3;
    private static final ThreadLocal<Integer> MANAGED_CREATION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ManagedSubLevelMassPolicy() {
    }

    /**
     * Marks the synchronous Sable allocation of an Antikythera SubLevel. Sable builds the initial
     * MassTracker from its observer callback before MechanismSubLevelService can install the normal
     * name/user-data ownership marker, so this short-lived context is required only during allocate.
     */
    public static <T> T duringManagedCreation(Supplier<T> action) {
        int previous = MANAGED_CREATION_DEPTH.get();
        MANAGED_CREATION_DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                MANAGED_CREATION_DEPTH.remove();
            } else {
                MANAGED_CREATION_DEPTH.set(previous);
            }
        }
    }

    /** Adds the structural mass to a freshly rebuilt self MassTracker when this is our SubLevel. */
    public static void applyStructuralMass(ServerSubLevel subLevel) {
        if (MANAGED_CREATION_DEPTH.get() <= 0 && !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        MassTracker tracker = subLevel.getSelfMassTracker();
        if (tracker == null) {
            return;
        }

        /*
         * Use two equal virtual point-block masses on opposite corners of the semantic 2x2x2
         * origin-frame volume. Their combined center is exactly plotCenter + (1,1,1), which is the
         * local anchor used by AssemblyPoseDriver. No block is written to the plot and these masses
         * therefore create no collision, raycast, rendering, ticking or FrameMask occupancy.
         */
        double halfMass = STRUCTURAL_MASS * 0.5;
        BlockPos lower = subLevel.getPlot().getCenterBlock();
        BlockPos upper = lower.offset(1, 1, 1);
        tracker.addBlockMass(subLevel.getLevel(), Blocks.STONE.defaultBlockState(), lower, halfMass, null);
        tracker.addBlockMass(subLevel.getLevel(), Blocks.STONE.defaultBlockState(), upper, halfMass, null);
    }
}
