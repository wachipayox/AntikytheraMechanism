package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.util.SableMathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.function.Supplier;

/**
 * Supplies a tiny non-colliding structural mass to Antikythera SubLevels.
 *
 * <p>Sable deliberately assigns zero mass to non-solid blocks and to empty plots. Rapier, however,
 * requires every registered rigid body to have a valid center of mass. Mechanism assemblies are
 * anchored and pose-driven by their parent Frames, so their Sable body needs only a numerically
 * valid inertial frame; it does not need a hidden physical block.</p>
 *
 * <p>The structural mass is an implementation detail of the managed child body. It is deliberately
 * removed again by {@link #payloadMassData(ServerSubLevel)} before the child's real physical mass
 * is projected into a foreign Sable host.</p>
 */
public final class ManagedSubLevelMassPolicy {
    static final double STRUCTURAL_MASS = 1.0E-3;
    private static final double MASS_EPSILON = 1.0E-10;
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

    /** True only on the server thread while Antikythera is synchronously allocating its Sable body. */
    public static boolean isManagedCreationActive() {
        return MANAGED_CREATION_DEPTH.get() > 0;
    }

    /** Adds the structural mass to a freshly rebuilt self MassTracker when this is our SubLevel. */
    public static void applyStructuralMass(ServerSubLevel subLevel) {
        if (!isManagedCreationActive() && !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
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

    /**
     * Returns the real mass distribution represented by a managed child, excluding the tiny virtual
     * mass used only to keep the pose-driven child numerically valid in Rapier.
     *
     * <p>The returned values stay in the child's unscaled local coordinates. Callers that project
     * them into another physical body must still apply the child's Pose3d scale/orientation.</p>
     */
    public static PayloadMassData payloadMassData(ServerSubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return null;
        }
        MassData total = subLevel.getMassTracker();
        if (total == null || total.getCenterOfMass() == null) {
            return null;
        }

        double totalMass = total.getMass();
        double payloadMass = totalMass - STRUCTURAL_MASS;
        if (!Double.isFinite(payloadMass) || payloadMass <= MASS_EPSILON) {
            return null;
        }

        Vector3d totalCenter = new Vector3d(total.getCenterOfMass());
        Vector3d structuralCenter = structuralCenter(subLevel);
        Vector3d payloadCenter = new Vector3d(totalCenter)
                .mul(totalMass)
                .fma(-STRUCTURAL_MASS, structuralCenter)
                .div(payloadMass);

        /*
         * total inertia is about totalCenter. Subtract the structural distribution after shifting
         * both the structural and payload distributions onto that same origin, leaving the payload
         * tensor about payloadCenter. This is the inverse of the same parallel-axis composition used
         * by Sable's MergedMassTracker.
         */
        Matrix3d payloadInertia = new Matrix3d(total.getInertiaTensor())
                .sub(structuralInertiaTensor());
        SableMathUtils.fmaInertiaTensor(
                new Vector3d(payloadCenter).sub(totalCenter),
                -payloadMass,
                payloadInertia);
        SableMathUtils.fmaInertiaTensor(
                new Vector3d(structuralCenter).sub(totalCenter),
                -STRUCTURAL_MASS,
                payloadInertia);

        return new PayloadMassData(payloadMass, payloadCenter, payloadInertia);
    }

    private static Vector3d structuralCenter(ServerSubLevel subLevel) {
        BlockPos center = subLevel.getPlot().getCenterBlock();
        return new Vector3d(center.getX() + 1.0, center.getY() + 1.0, center.getZ() + 1.0);
    }

    /** Exact inertia of the two virtual half-mass unit cubes about their shared center. */
    private static Matrix3d structuralInertiaTensor() {
        double diagonal = STRUCTURAL_MASS * (2.0 / 3.0);
        double offDiagonal = -STRUCTURAL_MASS * 0.25;
        Matrix3d tensor = new Matrix3d().zero();
        tensor.m00 = diagonal;
        tensor.m11 = diagonal;
        tensor.m22 = diagonal;
        tensor.m01 = offDiagonal;
        tensor.m10 = offDiagonal;
        tensor.m02 = offDiagonal;
        tensor.m20 = offDiagonal;
        tensor.m12 = offDiagonal;
        tensor.m21 = offDiagonal;
        return tensor;
    }

    public record PayloadMassData(double mass, Vector3d centerOfMass, Matrix3d inertiaTensor) {
    }
}
