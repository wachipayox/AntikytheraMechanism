package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable currently checks cross-SubLevel placement with unit-sized oriented boxes and explicitly
 * does not account for differing collision shapes. A 0.5-scale mini block therefore collides with
 * our hollow full-size frame, and can also falsely block a normal block next to that frame.
 *
 * <p>Use vanilla's replacement check only in the two Antikythera cases where that generic OBB test
 * is known to be invalid. BlockItem#canPlace still performs the normal survival/entity-obstruction
 * checks afterwards.</p>
 */
@Mixin(value = BlockPlaceContext.class, priority = 2000)
abstract class BlockPlaceContextManagedCollisionMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$ignoreUnscaledSableObb(CallbackInfoReturnable<Boolean> callback) {
        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();

        boolean managedMiniTarget = MiniWorldEnvironment.isManagedMiniPosition(level, target);
        boolean normalTargetBesideFrame = false;
        if (!managedMiniTarget && Sable.HELPER.getContaining(level, target) == null) {
            for (Direction direction : Direction.values()) {
                if (level.getBlockState(target.relative(direction)).is(ModRegistries.MECHANISM_FRAME.get())) {
                    normalTargetBesideFrame = true;
                    break;
                }
            }
        }

        if (!managedMiniTarget && !normalTargetBesideFrame) {
            return;
        }

        callback.setReturnValue(
                context.replacingClickedOnBlock()
                        || level.getBlockState(target).canBeReplaced(context));
    }
}
