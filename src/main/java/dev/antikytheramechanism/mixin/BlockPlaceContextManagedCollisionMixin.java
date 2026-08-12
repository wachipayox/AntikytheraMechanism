package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable currently checks cross-SubLevel placement with unit-sized oriented boxes and explicitly
 * does not account for differing collision shapes. A 0.5-scale mini block therefore collides with
 * our hollow full-size frame, and can also falsely block a normal block next to that frame.
 *
 * <p>This must run on the final return value rather than at HEAD. Sable itself injects a cancellable
 * HEAD check into the same method; overriding at HEAD allowed its later injector to cancel our
 * result again. Post-processing the final value makes the exception deterministic while keeping
 * vanilla replacement/survival checks in BlockItem intact.</p>
 */
@Mixin(value = BlockPlaceContext.class, priority = 2000)
abstract class BlockPlaceContextManagedCollisionMixin {
    @ModifyReturnValue(method = "canPlace", at = @At("RETURN"))
    private boolean antikytheramechanism$ignoreUnscaledSableObb(boolean original) {
        if (original) {
            return true;
        }

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
            return false;
        }

        return context.replacingClickedOnBlock()
                || level.getBlockState(target).canBeReplaced(context);
    }
}
