package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable's cross-Level placement check currently models every involved block as a 1x1x1 OBB and
 * explicitly does not account for differing collision shapes or scale. Antikythera's 0.5 SubLevels
 * must therefore not veto parent-world block placement merely because their broadphase overlaps a
 * target, and parent blocks/Frames must not veto a valid placement inside our mini world.
 *
 * <p>Sable cancels {@link BlockPlaceContext#canPlace()} from a HEAD injector. A RETURN-value patch
 * cannot repair that result because the cancelled path never reaches the method return bytecode.
 * For Antikythera-managed targets we therefore short-circuit first, using exactly vanilla's
 * replacement rule, before Sable's scale-unaware OBB test can run.</p>
 *
 * <p>This changes only BlockPlaceContext placement eligibility. Entity/player collision with real
 * mini block shapes remains entirely handled by Sable.</p>
 */
@Mixin(value = BlockPlaceContext.class, priority = 2000)
abstract class BlockPlaceContextManagedCollisionMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$ignoreManagedSubLevelBroadphase(
            CallbackInfoReturnable<Boolean> callback) {
        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();

        boolean managedTarget = MiniWorldEnvironment.isManagedMiniPosition(level, target);
        boolean normalTargetAffectedOnlyByManagedSubLevels = false;

        if (!managedTarget && Sable.HELPER.getContaining(level, target) == null) {
            boolean intersectsManaged = false;
            boolean intersectsForeign = false;
            for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(target))) {
                if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                    intersectsManaged = true;
                } else {
                    intersectsForeign = true;
                }
            }
            normalTargetAffectedOnlyByManagedSubLevels = intersectsManaged && !intersectsForeign;
        }

        if (!managedTarget && !normalTargetAffectedOnlyByManagedSubLevels) {
            return;
        }

        // This is vanilla BlockPlaceContext#canPlace. Entity obstruction is checked separately by
        // BlockItem/Level and remains Sable-aware; only Sable's incorrect cross-level block OBB veto
        // is bypassed here.
        boolean vanillaCanReplace = context.replacingClickedOnBlock()
                || level.getBlockState(target).canBeReplaced(context);
        callback.setReturnValue(vanillaCanReplace);
    }
}
