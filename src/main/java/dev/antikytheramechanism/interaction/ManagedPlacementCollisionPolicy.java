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
 * 0.5-scale mini world: a real parent block can overlap the SubLevel broadphase without overlapping
 * any actual mini collision shape, and a valid mini placement can be vetoed by the parent Frame.
 *
 * <p>This policy bypasses only that BlockPlaceContext broadphase. BlockItem still performs normal
 * state survival, entity obstruction and the final setBlock write.</p>
 */
public final class ManagedPlacementCollisionPolicy {
    private static final double QUERY_EPSILON = 1.0E-6;

    private ManagedPlacementCollisionPolicy() {
    }

    public static boolean shouldUseVanillaContextCanPlace(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();

        SubLevel containing = Sable.HELPER.getContaining(level, target);
        if (containing != null) {
            return MiniWorldEnvironment.isManagedSubLevel(containing);
        }

        boolean foundManaged = false;
        BoundingBox3d targetBounds = new BoundingBox3d(target).expand(QUERY_EPSILON);
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, targetBounds)) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                foundManaged = true;
            } else {
                // Never weaken placement rules for somebody else's Sable SubLevel.
                return false;
            }
        }
        return foundManaged;
    }

    /** Exact vanilla BlockPlaceContext#canPlace logic, before Sable injects its cross-level OBB veto. */
    public static boolean vanillaContextCanPlace(BlockPlaceContext context) {
        return context.replacingClickedOnBlock()
                || context.getLevel().getBlockState(context.getClickedPos()).canBeReplaced(context);
    }
}
