package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.ManagedRapierBounds;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.sablescale.scale.ScaledColliders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents Sable Scale from reconstructing Sable's EMPTY plot sentinel into enormous native bounds.
 *
 * <p>For scaled bodies Sable Scale deliberately ignores the integer bounds passed by
 * RapierPhysicsPipeline#onStatsChanged and recomputes them from the plot in
 * ScaledColliders#scaledLocalBounds. Empty plots use an inverted integer sentinel, so the guard must
 * live at that recomputation point rather than around RapierPhysicsPipeline's original native call.</p>
 */
@Mixin(value = ScaledColliders.class, priority = 2000, remap = false)
abstract class ScaledCollidersManagedEmptyBoundsMixin {
    @Inject(method = "scaledLocalBounds", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$finiteManagedEmptyBounds(
            ServerSubLevel subLevel,
            CallbackInfoReturnable<int[]> cir) {
        ManagedRapierBounds.NativeBounds safe = ManagedRapierBounds.finiteEmptyBounds(subLevel);
        if (safe == null) {
            return;
        }

        cir.setReturnValue(new int[]{
                safe.minX(), safe.minY(), safe.minZ(),
                safe.maxX(), safe.maxY(), safe.maxZ()
        });
    }
}
