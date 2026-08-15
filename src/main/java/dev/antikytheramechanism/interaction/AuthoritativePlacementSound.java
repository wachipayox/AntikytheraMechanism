package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Marks placement feedback that Antikythera intentionally makes authoritative on the server.
 *
 * <p>Vanilla BlockItem placement excludes the placing player from the server sound broadcast because
 * the normal client-side placement path already played the same sound predictively. Several
 * Antikythera routes deliberately consume client prediction without mutating the client plot, so the
 * server must include that player. Those sounds are also projected out of Sable plot storage before
 * broadcasting: the first mini block can create its SubLevel only moments before the sound packet,
 * and a client is not guaranteed to know that plot mapping yet.</p>
 */
public final class AuthoritativePlacementSound {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AuthoritativePlacementSound() {
    }

    public static <T> T includePlacingPlayer(Supplier<T> action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }

    public static boolean shouldIncludePlacingPlayer() {
        return DEPTH.get() > 0;
    }

    /** Converts a plot-storage block centre to the physical/world point where its sound belongs. */
    public static Vec3 physicalSoundPosition(Level level, BlockPos position) {
        return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(position));
    }

    /**
     * A normal mini-on-mini BlockItem placement usually gets vanilla client prediction and therefore
     * must keep excluding that client from the server sound. Sable-hosted Frames are the exception:
     * the client still returns the placement swing, but the placement sound can remain attached to
     * plot storage instead of the physical hosted Frame. Compensate only that server-side case.
     */
    public static boolean shouldCompensateForeignHostedManagedPlacement(Level level, BlockPos clickedMiniPosition) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SubLevel containing = Sable.HELPER.getContaining(serverLevel, clickedMiniPosition);
        if (!(containing instanceof ServerSubLevel child) || !MiniWorldEnvironment.isManagedSubLevel(child)) {
            return false;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (ownerId == null) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(serverLevel).getAssembly(ownerId).orElse(null);
        return assembly != null
                && MechanismAssemblyHost.resolve(serverLevel, assembly.origin()).kind()
                        == MechanismAssemblyHost.Kind.FOREIGN;
    }
}
