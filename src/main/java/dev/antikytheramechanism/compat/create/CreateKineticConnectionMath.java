package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Physical-lattice form of Create 6.0.10's ordinary cog connection rules.
 *
 * <p>Create keeps the corresponding helpers private inside RotationPropagator, so cross-SubLevel
 * links cannot call them directly. Keep this as the single compatibility copy used by both
 * Frame-to-Frame and Transmission-Box bridges rather than letting each bridge reimplement a partial
 * subset of the rules.</p>
 */
public final class CreateKineticConnectionMath {
    private CreateKineticConnectionMath() {
    }

    public enum CogKind {
        NONE,
        SMALL,
        LARGE
    }

    public static CogKind cogKind(BlockState state) {
        if (ICogWheel.isLargeCog(state)) {
            return CogKind.LARGE;
        }
        if (ICogWheel.isSmallCog(state)) {
            return CogKind.SMALL;
        }
        return CogKind.NONE;
    }

    /**
     * Matches RotationPropagator's SMALL/SMALL, LARGE/SMALL and perpendicular LARGE/LARGE branches.
     * The returned value is target RPM per source RPM in the same scale.
     */
    public static float cogModifier(
            CogKind fromKind,
            Direction.Axis fromAxis,
            CogKind toKind,
            Direction.Axis toAxis,
            BlockPos diff) {
        if (fromKind == CogKind.NONE || toKind == CogKind.NONE || diff.equals(BlockPos.ZERO)) {
            return 0.0F;
        }

        // Large Gear <-> Large Gear. Create requires perpendicular axes and one block of separation
        // along each cog axis, with zero separation along the remaining axis.
        if (fromKind == CogKind.LARGE && toKind == CogKind.LARGE) {
            if (fromAxis == toAxis || !isLargeToLarge(diff, fromAxis, toAxis)) {
                return 0.0F;
            }
            int fromAxisDiff = fromAxis.choose(diff.getX(), diff.getY(), diff.getZ());
            int toAxisDiff = toAxis.choose(diff.getX(), diff.getY(), diff.getZ());
            return (fromAxisDiff > 0) ^ (toAxisDiff > 0) ? -1.0F : 1.0F;
        }

        // Large Gear <-> Small Gear. Both rotate around the same axis and are diagonally adjacent in
        // the plane perpendicular to that axis.
        if (fromKind == CogKind.LARGE && toKind == CogKind.SMALL
                && fromAxis == toAxis
                && isLargeToSmall(diff, fromAxis)) {
            return -2.0F;
        }
        if (fromKind == CogKind.SMALL && toKind == CogKind.LARGE
                && fromAxis == toAxis
                && isLargeToSmall(diff, fromAxis)) {
            return -0.5F;
        }

        // Small Gear <-> Small Gear. Native Create only meshes cardinal neighbours whose common cog
        // axis is perpendicular to the neighbour direction.
        if (fromKind == CogKind.SMALL && toKind == CogKind.SMALL && fromAxis == toAxis) {
            if (diff.distManhattan(BlockPos.ZERO) != 1) {
                return 0.0F;
            }
            Direction direction = Direction.getNearest(diff.getX(), diff.getY(), diff.getZ());
            return direction.getAxis() == fromAxis ? 0.0F : -1.0F;
        }

        return 0.0F;
    }

    /** Ordinary shaft/cog neighbours never need a physical half-grid delta beyond this 3x3x3 shell. */
    public static boolean isPotentialStandardNeighbour(BlockPos diff) {
        int x = Math.abs(diff.getX());
        int y = Math.abs(diff.getY());
        int z = Math.abs(diff.getZ());
        int manhattan = x + y + z;
        return x <= 1 && y <= 1 && z <= 1 && (manhattan == 1 || manhattan == 2);
    }

    private static boolean isLargeToLarge(
            BlockPos diff,
            Direction.Axis fromAxis,
            Direction.Axis toAxis) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            int axisDiff = axis.choose(diff.getX(), diff.getY(), diff.getZ());
            if (axis == fromAxis || axis == toAxis) {
                if (axisDiff == 0) {
                    return false;
                }
            } else if (axisDiff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLargeToSmall(BlockPos diff, Direction.Axis axis) {
        if (axis.choose(diff.getX(), diff.getY(), diff.getZ()) != 0) {
            return false;
        }
        for (Direction.Axis candidate : Direction.Axis.values()) {
            if (candidate == axis) {
                continue;
            }
            if (Math.abs(candidate.choose(diff.getX(), diff.getY(), diff.getZ())) != 1) {
                return false;
            }
        }
        return true;
    }
}
