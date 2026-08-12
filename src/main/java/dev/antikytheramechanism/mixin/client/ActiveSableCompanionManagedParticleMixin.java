package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedMiniParticleSpawnContext;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents parent-world destruction debris from being mistaken for Antikythera plot-local particles.
 *
 * <p>Sable's Particle constructor and its initial kick-out both call the real
 * ActiveSableCompanion#getContainingClient(Position) API. During a parent-world block destroy that
 * Antikythera has already classified as managed-only, an Antikythera result is semantically wrong:
 * those coordinates are already global. Return null only for our SubLevel in that short construction
 * context. Foreign SubLevels retain Sable's original classification.</p>
 */
@Mixin(value = ActiveSableCompanion.class, remap = false)
abstract class ActiveSableCompanionManagedParticleMixin {
    @Inject(
            method = "getContainingClient(Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void antikytheramechanism$ignoreManagedForParentDebris(
            Position position,
            CallbackInfoReturnable<ClientSubLevel> callback) {
        if (!ManagedMiniParticleSpawnContext.shouldDetachParentTerrainParticles()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        // Use the distinct Level+Position overload so this check does not recurse into this injector.
        SubLevel candidate = Sable.HELPER.getContaining(level, position);
        if (MiniWorldEnvironment.isManagedSubLevel(candidate)) {
            callback.setReturnValue(null);
        }
    }
}
