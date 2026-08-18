package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/**
 * Converts Sable's empty plot sentinel into finite bounds only at the Rapier/Sable Scale native boundary.
 *
 * <p>{@code BoundingBox3i.EMPTY} intentionally uses Integer.MAX_VALUE minima and
 * Integer.MIN_VALUE maxima. That is a useful Java-side sentinel but must never be forwarded as a
 * real rigid-body bounding box. The public Sable plot/global bounds stay empty, so this helper does
 * not create a Minecraft collision, raycast or placement volume.</p>
 */
public final class ManagedRapierBounds {
    private ManagedRapierBounds() {
    }

    public static @Nullable NativeBounds finiteEmptyBounds(ServerSubLevel subLevel) {
        /*
         * Both Antikythera body types have a short allocation window before their durable identity is
         * installed. Managed Frame children use ManagedSubLevelMassPolicy's creation context;
         * detached mini-physics bodies use DetachedMiniPhysicsSubLevelService's creation context and,
         * after adoption, their persisted detached marker. Do not infer ownership from scale alone:
         * unrelated Sable/Sable Scale bodies are outside this workaround.
         */
        if (!ManagedSubLevelMassPolicy.isManagedCreationActive()
                && !MiniWorldEnvironment.isManagedSubLevel(subLevel)
                && !DetachedMiniPhysicsSubLevelService.isDetachedCreationActive()
                && !DetachedMiniPhysicsSubLevelService.isDetached(subLevel)) {
            return null;
        }

        BoundingBox3ic plotBounds = subLevel.getPlot().getBoundingBox();
        if (!isEmptySentinel(plotBounds)) {
            return null;
        }

        Vector3dc centerOfMass = subLevel.getMassTracker() == null
                ? null
                : subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass != null
                && Double.isFinite(centerOfMass.x())
                && Double.isFinite(centerOfMass.y())
                && Double.isFinite(centerOfMass.z())) {
            return finitePointBounds(centerOfMass.x(), centerOfMass.y(), centerOfMass.z());
        }

        // Structural mass should normally provide a valid CoM before Sable Scale asks for local
        // bounds. Keep a deterministic finite fallback so initialization ordering can never forward
        // the EMPTY sentinel into native code.
        BlockPos center = subLevel.getPlot().getCenterBlock().offset(1, 1, 1);
        return finitePointBounds(center.getX(), center.getY(), center.getZ());
    }

    public static boolean isEmptySentinel(BoundingBox3ic bounds) {
        return bounds == null
                || bounds.minX() > bounds.maxX()
                || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ();
    }

    static NativeBounds finitePointBounds(double x, double y, double z) {
        int blockX = Mth.floor(x);
        int blockY = Mth.floor(y);
        int blockZ = Mth.floor(z);
        // Rapier documents local bounds as inclusive, so min == max is one finite local cell. There
        // are still no voxel colliders in an empty plot; this is bookkeeping only.
        return new NativeBounds(blockX, blockY, blockZ, blockX, blockY, blockZ);
    }

    public record NativeBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
