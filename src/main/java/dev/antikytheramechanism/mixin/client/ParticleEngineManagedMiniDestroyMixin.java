package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.client.ManagedMiniParticleSpawnContext;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.registry.ModRegistries;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Generates scale-correct mini destruction debris and cheaply classifies parent-world block debris.
 *
 * <p>Vanilla samples a logical 1x1x1 block at 0.25-block spacing. A mini block is still 1x1x1 in
 * plot coordinates, so the unmodified path creates 4x4x4 = 64 fragments and only afterwards packs
 * them into a 0.5x0.5x0.5 world-space volume. We instead choose the sample count from the real
 * world-space dimensions: a full block at scale 0.5 produces 2x2x2 = 8 fragments.</p>
 *
 * <p>For an ordinary parent-world block near a Mechanism Frame, classify the whole destroy
 * operation from the real parent blocks instead of asking Sable's transformed-sublevel broadphase.
 * Every fragment is therefore born as ordinary world-space debris and is permanently detached before
 * its first tick. This is important because Sable's particle movement performs transformed
 * intersection, raycast and collision work once world-space debris reaches a SubLevel; dozens of
 * vanilla fragments doing that at once can be extremely expensive. Foreign Sable SubLevels retain
 * their normal behaviour because parent detachment is only enabled when the source is not in any
 * SubLevel and an actual Mechanism Frame is close to the destroyed block.</p>
 */
@Mixin(value = ParticleEngine.class, priority = 2000)
abstract class ParticleEngineManagedMiniDestroyMixin {
    private static final double VANILLA_PARTICLE_SPACING = 0.25;
    private static final int PARENT_DEBRIS_FRAME_RADIUS = 3;

    @Shadow
    protected ClientLevel level;

    @Shadow
    public abstract void add(Particle particle);

    @WrapMethod(method = "destroy")
    private void antikytheramechanism$routeDestroyEffects(
            BlockPos pos,
            BlockState state,
            Operation<Void> original) {
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(pos);
        if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            antikytheramechanism$destroyManagedMiniBlock(pos, state, subLevel);
            return;
        }

        // A non-managed SubLevel belongs to Sable or another integration and must keep Sable's
        // native particle behaviour. Only true parent-world destroys next to our physical Frames
        // are detached from transformed-sublevel particle processing.
        if (subLevel == null && antikytheramechanism$hasNearbyMechanismFrame(pos)) {
            ManagedMiniParticleSpawnContext.duringParentTerrainDetach(
                    () -> original.call(pos, state));
            return;
        }

        original.call(pos, state);
    }

    @Unique
    private void antikytheramechanism$destroyManagedMiniBlock(
            BlockPos pos,
            BlockState state,
            ClientSubLevel subLevel) {
        if (state.isAir()) {
            return;
        }

        ParticleEngine self = (ParticleEngine) (Object) this;
        if (IClientBlockExtensions.of(state).addDestroyEffects(state, this.level, pos, self)) {
            return;
        }

        Vector3dc scale = subLevel.logicalPose().scale();
        double scaleX = Math.abs(scale.x());
        double scaleY = Math.abs(scale.y());
        double scaleZ = Math.abs(scale.z());
        float particleScale = (float) Math.cbrt(Math.max(1.0E-9, scaleX * scaleY * scaleZ));

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

                        double localX = pos.getX() + xFraction * localWidth + minX;
                        double localY = pos.getY() + yFraction * localHeight + minY;
                        double localZ = pos.getZ() + zFraction * localDepth + minZ;
                        double localVelocityX = xFraction - 0.5;
                        double localVelocityY = yFraction - 0.5;
                        double localVelocityZ = zFraction - 0.5;

                        ManagedMiniParticleSpawnContext.duringSableKickOut(() -> {
                            TerrainParticle particle = new TerrainParticle(
                                    this.level,
                                    localX,
                                    localY,
                                    localZ,
                                    localVelocityX,
                                    localVelocityY,
                                    localVelocityZ,
                                    state,
                                    pos).updateSprite(state, pos);

                            // Sable's add TAIL performs the one and only local -> global projection.
                            this.add(particle);

                            ((ManagedTerrainParticleState) particle)
                                    .antikytheramechanism$markDetachedFromSubLevel();
                            particle.scale(particleScale);
                        });
                    }
                }
            }
        });
    }

    @Unique
    private boolean antikytheramechanism$hasNearbyMechanismFrame(BlockPos parentBlockPos) {
        BlockPos min = parentBlockPos.offset(
                -PARENT_DEBRIS_FRAME_RADIUS,
                -PARENT_DEBRIS_FRAME_RADIUS,
                -PARENT_DEBRIS_FRAME_RADIUS);
        BlockPos max = parentBlockPos.offset(
                PARENT_DEBRIS_FRAME_RADIUS,
                PARENT_DEBRIS_FRAME_RADIUS,
                PARENT_DEBRIS_FRAME_RADIUS);

        for (BlockPos candidate : BlockPos.betweenClosed(min, max)) {
            if (this.level.hasChunkAt(candidate)
                    && this.level.getBlockState(candidate).is(ModRegistries.MECHANISM_FRAME.get())) {
                return true;
            }
        }
        return false;
    }
}
