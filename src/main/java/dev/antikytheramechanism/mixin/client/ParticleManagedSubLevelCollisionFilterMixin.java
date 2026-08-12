package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Terrain debris does not need physical interaction with Antikythera's miniature world.
 *
 * <p>Sable's generic particle mover queries every intersecting SubLevel and then performs local
 * raycasts plus voxel collision resolution for each candidate. That is useful for ordinary moving
 * Sable vessels, but Antikythera mechanisms are stationary, frame-contained miniature worlds. In
 * particular, vanilla destruction particles from a parent-world block next to a Mechanism Frame
 * can otherwise enter the expensive Sable path even though they did not originate in the mini
 * world at all.</p>
 *
 * <p>Filter only Antikythera SubLevels from TerrainParticle collision candidates. Other Sable
 * SubLevels remain untouched, and non-terrain particles retain stock Sable behavior.</p>
 */
@Mixin(value = Particle.class, priority = 500)
abstract class ParticleManagedSubLevelCollisionFilterMixin {
    @WrapOperation(
            method = "sable$moveWithSubLevels",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;",
                    remap = false))
    private Iterable<SubLevel> antikytheramechanism$ignoreManagedTerrainParticleCollisions(
            ActiveSableCompanion helper,
            Level level,
            BoundingBox3dc bounds,
            Operation<Iterable<SubLevel>> original) {
        Iterable<SubLevel> candidates = original.call(helper, level, bounds);
        if (!((Object) this instanceof TerrainParticle)) {
            return candidates;
        }

        ObjectArrayList<SubLevel> filtered = new ObjectArrayList<>();
        for (SubLevel candidate : candidates) {
            if (!MiniWorldEnvironment.isManagedSubLevel(candidate)) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }
}
