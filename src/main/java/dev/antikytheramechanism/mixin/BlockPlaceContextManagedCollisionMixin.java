package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable's cross-Level placement check currently models every involved block as a 1x1x1 OBB and
 * explicitly does not account for differing collision shapes or scale. Antikythera's 0.5 SubLevels
 * must therefore not veto parent-world block placement merely because their broadphase overlaps a
 * target, and parent blocks/Frames must not veto a valid placement inside our mini world.
 *
 * <p>This mixin deliberately runs after Sable's default-priority HEAD injector. At a higher mixin
 * priority Sable adds its cancellable early-return after this transformation, which means the false
 * result bypasses our return-value correction entirely.</p>
 *
 * <p>This changes only BlockPlaceContext placement eligibility. Entity/player collision with real
 * mini block shapes remains entirely handled by Sable.</p>
 */
@Mixin(value = BlockPlaceContext.class, priority = 900)
abstract class BlockPlaceContextManagedCollisionMixin {
    @ModifyReturnValue(method = "canPlace", at = @At("RETURN"))
    private boolean antikytheramechanism$ignoreManagedSubLevelBroadphase(boolean original) {
        if (original) {
            return true;
        }

        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        boolean vanillaCanReplace = context.replacingClickedOnBlock()
                || level.getBlockState(target).canBeReplaced(context);
        if (!vanillaCanReplace) {
            return false;
        }

        // A placement whose target itself is in our plot should obey the ordinary replacement rule;
        // Sable's parent-world/frame OBB must not make it fail.
        if (MiniWorldEnvironment.isManagedMiniPosition(level, target)) {
            return true;
        }

        // Never weaken another SubLevel implementation. For a normal-world target, only discard
        // Sable's negative result when a managed Antikythera SubLevel is the sole intersecting
        // SubLevel responsible for the cross-level broadphase test.
        if (Sable.HELPER.getContaining(level, target) != null) {
            return false;
        }

        boolean intersectsManaged = false;
        boolean intersectsForeign = false;
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(target))) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                intersectsManaged = true;
            } else {
                intersectsForeign = true;
            }
        }
        return intersectsManaged && !intersectsForeign;
    }
}
