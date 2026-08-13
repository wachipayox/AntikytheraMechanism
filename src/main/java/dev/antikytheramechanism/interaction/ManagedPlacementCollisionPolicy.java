package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;

/**
 * Decides when Sable's scale-unaware cross-level block-placement broadphase must be skipped.
 *
 * <p>Sable models both the placed block and every candidate block as full 1x1x1 oriented boxes.
 * That is deliberately conservative for ordinary SubLevels, but it is incorrect for Antikythera's
 * 0.5-scale mini world: a real host block can overlap the managed child broadphase without
 * overlapping any actual mini collision shape. The Frame itself is the authoritative reservation
 * of host space, so a managed child must never veto placement in its root or foreign host.
 *
 * <p>This policy bypasses only that BlockPlaceContext broadphase. BlockItem still performs normal
 * state survival, entity obstruction and the final setBlock write. A foreign SubLevel other than
 * the physical host remains authoritative and keeps Sable's normal cross-level veto.</p>
 */
public final class ManagedPlacementCollisionPolicy {
    private static final double QUERY_EPSILON = 1.0E-6;

    private ManagedPlacementCollisionPolicy() {
    }

    public static boolean shouldUseVanillaContextCanPlace(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        SubLevel physicalHost = Sable.HELPER.getContaining(level, target);

        // Placements inside our own child still need the original mini-world bypass: the parent
        // Frame is allowed to overlap its 0.5-scale content and must not veto mini placement.
        if (physicalHost != null && MiniWorldEnvironment.isManagedSubLevel(physicalHost)) {
            return true;
        }

        boolean foundManagedChild = false;
        BoundingBox3d targetBounds = new BoundingBox3d(target).expand(QUERY_EPSILON);
        for (SubLevel candidate : Sable.HELPER.getAllIntersecting(level, targetBounds)) {
            // A block being placed inside a foreign host naturally overlaps that host. This is not a
            // cross-level collision; the host's ordinary BlockItem/state checks remain authoritative.
            if (candidate == physicalHost) {
                continue;
            }
            if (MiniWorldEnvironment.isManagedSubLevel(candidate)) {
                foundManagedChild = true;
                continue;
            }

            // Never weaken placement rules against a genuinely separate foreign SubLevel.
            return false;
        }
        return foundManagedChild;
    }

    /** Exact vanilla BlockPlaceContext#canPlace logic, before Sable injects its cross-level OBB veto. */
    public static boolean vanillaContextCanPlace(BlockPlaceContext context) {
        return context.replacingClickedOnBlock()
                || context.getLevel().getBlockState(context.getClickedPos()).canBeReplaced(context);
    }
}
