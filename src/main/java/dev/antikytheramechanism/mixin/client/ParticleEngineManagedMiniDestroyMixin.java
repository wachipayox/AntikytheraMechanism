package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Generates Antikythera destruction debris directly in parent-world coordinates.
 *
 * <p>Vanilla ParticleEngine.destroy samples a full local 1x1x1 block at 0.25-block spacing. A mini
 * block is still 1x1x1 inside Sable's plot, so the ordinary path creates roughly 64 particles and
 * only afterwards projects them into a 0.5x0.5x0.5 world-space volume. That is eight times the
 * intended world-space particle density, with full-size particle quads, and also sends every
 * fragment through Sable's initial particle bookkeeping.</p>
 *
 * <p>For a managed ClientSubLevel we reproduce vanilla's shape sampling using the <em>world-space</em>
 * dimensions of each shape box, transform each sample into the parent world before constructing the
 * TerrainParticle, scale its visual/collision size with the SubLevel, and mark it permanently
 * detached. At Antikythera's 0.5 scale a full cube therefore produces 2x2x2 = 8 fragments instead of
 * 4x4x4 = 64.</p>
 */
@Mixin(value = ParticleEngine.class, priority = 2000)
abstract class ParticleEngineManagedMiniDestroyMixin {
    private static final double VANILLA_PARTICLE_SPACING = 0.25;

    @Shadow
    protected ClientLevel level;

    @Shadow
    public abstract void add(Particle particle);

    @Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$destroyMiniBlockInParentWorld(
            BlockPos pos,
            BlockState state,
            CallbackInfo callback) {
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(pos);
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        // We own the managed-mini destroy path from this point onward. Preserve NeoForge's custom
        // destroy-effect hook exactly once; custom effects that claim the event remain authoritative.
        if (state.isAir()) {
            callback.cancel();
            return;
        }
        ParticleEngine self = (ParticleEngine) (Object) this;
        if (IClientBlockExtensions.of(state).addDestroyEffects(state, this.level, pos, self)) {
            callback.cancel();
            return;
        }

        Vector3dc scale = subLevel.logicalPose().scale();
        double scaleX = Math.abs(scale.x());
        double scaleY = Math.abs(scale.y());
        double scaleZ = Math.abs(scale.z());
        float particleScale = (float) Math.cbrt(Math.max(1.0E-9, scaleX * scaleY * scaleZ));
        BlockPos worldSourcePos = BlockPos.containing(
                subLevel.logicalPose().transformPosition(Vec3.atCenterOf(pos)));

        VoxelShape shape = state.getShape(this.level, pos);
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double localWidth = Math.min(1.0, maxX - minX);
            double localHeight = Math.min(1.0, maxY - minY);
            double localDepth = Math.min(1.0, maxZ - minZ);

            int countX = Math.max(2, Mth.ceil(localWidth * scaleX / VANILLA_PARTICLE_SPACING));
            int countY = Math.max(2, Mth.ceil(localHeight * scaleY / VANILLA_PARTICLE_SPACING));
            int countZ = Math.max(2, Mth.ceil(localDepth * scaleZ / VANILLA_PARTICLE_SPACING));

            for (int xIndex = 0; xIndex < countX; xIndex++) {
                for (int yIndex = 0; yIndex < countY; yIndex++) {
                    for (int zIndex = 0; zIndex < countZ; zIndex++) {
                        double xFraction = ((double) xIndex + 0.5) / (double) countX;
                        double yFraction = ((double) yIndex + 0.5) / (double) countY;
                        double zFraction = ((double) zIndex + 0.5) / (double) countZ;

                        Vec3 localPosition = new Vec3(
                                pos.getX() + xFraction * localWidth + minX,
                                pos.getY() + yFraction * localHeight + minY,
                                pos.getZ() + zFraction * localDepth + minZ);
                        Vec3 worldPosition = subLevel.logicalPose().transformPosition(localPosition);
                        Vec3 worldVelocity = subLevel.logicalPose().transformNormal(new Vec3(
                                xFraction - 0.5,
                                yFraction - 0.5,
                                zFraction - 0.5));

                        TerrainParticle particle = new TerrainParticle(
                                this.level,
                                worldPosition.x,
                                worldPosition.y,
                                worldPosition.z,
                                worldVelocity.x,
                                worldVelocity.y,
                                worldVelocity.z,
                                state,
                                worldSourcePos).updateSprite(state, pos);
                        ((ManagedTerrainParticleState) particle)
                                .antikytheramechanism$markDetachedFromSubLevel();
                        particle.scale(particleScale);
                        this.add(particle);
                    }
                }
            }
        });

        callback.cancel();
    }
}
