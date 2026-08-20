package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.compat.offroad.OffroadGroundContactDiagnostics;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Observes the exact hard-contact batch that Sable's Rapier backend already consumes for collision
 * effects. The array is returned unchanged, so normal Sable collision handling remains authoritative.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
abstract class RapierGroundContactCaptureMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Int2ObjectMap<ServerSubLevel> activeSubLevels;

    @Redirect(
            method = "processCollisionEffects",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;clearCollisions(J)[D"),
            require = 1)
    private double[] antikytheramechanism$captureHardContacts(long sceneHandle) {
        double[] collisions = Rapier3DInvoker.antikytheramechanism$clearCollisions(sceneHandle);
        OffroadGroundContactDiagnostics.captureGroundContacts(this.level, this.activeSubLevels, collisions);
        return collisions;
    }
}
