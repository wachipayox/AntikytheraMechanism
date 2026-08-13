package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.client.ManagedMiniParticleSpawnContext;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Generates scale-correct mini destruction debris and keeps parent-world terrain debris near a
 * Mechanism Frame completely outside Sable's transformed particle pipeline.
 *
 * <p>The parent fast path deliberately mirrors NeoForge 1.21.1's ParticleEngine#destroy algorithm
 * instead of delegating back to the transformed method under a ThreadLocal. That makes ownership
 * deterministic: proximity is tested once per destroyed block, and every vanilla TerrainParticle is
 * marked as parent-world debris before ParticleEngine#add lets Sable inspect it. We therefore avoid
 * both transformed broadphase work and the old per-particle 7x7x7 Frame search.</p>
 */
@Mixin(value = ParticleEngine.class, priority = 2000)
abstract class ParticleEngineManagedMiniDestroyMixin {
    @Unique
    private static final double ANTIKYTHERA_VANILLA_PARTICLE_SPACING = 0.25;
    @Unique
    private static final int ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS = 3;

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
        if (ManagedClientSubLevelIdentity.isManaged(subLevel)) {
            antikytheramechanism$destroyManagedMiniBlock(pos, state, subLevel);
            return;
        }

        // Foreign Sable SubLevels keep Sable's own particle semantics. A true parent-world block
        // near one of our Frames uses a deterministic vanilla-equivalent debris generator instead.
        if (subLevel == null && antikytheramechanism$hasNearbyMechanismFrame(pos)) {
            antikytheramechanism$destroyParentBlockNearFrame(pos, state);
            return;
        }

        original.call(pos, state);
    }

    /**
     * Standard hit/crack particles are only one TerrainParticle, but they still need to be born with
     * parent-world ownership when the player mines beside a Frame. Keep the context around the whole
     * synchronous vanilla crack call instead of doing a spatial Frame search in every constructor.
     */
    @WrapMethod(method = "crack")
    private void antikytheramechanism$routeCrackEffects(
            BlockPos pos,
            Direction direction,
            Operation<Void> original) {
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(pos);
        if (subLevel == null && antikytheramechanism$hasNearbyMechanismFrame(pos)) {
            ManagedMiniParticleSpawnContext.duringParentTerrainDetach(
                    () -> original.call(pos, direction));
            return;
        }
        original.call(pos, direction);
    }

    @Unique
    private void antikytheramechanism$destroyParentBlockNearFrame(BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return;
        }

        ParticleEngine self = (ParticleEngine) (Object) this;
        boolean[] extensionHandled = new boolean[1];
        ManagedMiniParticleSpawnContext.duringParentTerrainDetach(() ->
                extensionHandled[0] = IClientBlockExtensions.of(state)
                        .addDestroyEffects(state, this.level, pos, self));
        if (extensionHandled[0]) {
            return;
        }

        VoxelShape shape = state.getShape(this.level, pos);
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double width = Math.min(1.0, maxX - minX);
            double height = Math.min(1.0, maxY - minY);
            double depth = Math.min(1.0, maxZ - minZ);

            int countX = Math.max(2, Mth.ceil(width / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));
            int countY = Math.max(2, Mth.ceil(height / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));
            int countZ = Math.max(2, Mth.ceil(depth / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));

            for (int xIndex = 0; xIndex < countX; xIndex++) {
                for (int yIndex = 0; yIndex < countY; yIndex++) {
                    for (int zIndex = 0; zIndex < countZ; zIndex++) {
                        double xFraction = ((double) xIndex + 0.5) / (double) countX;
                        double yFraction = ((double) yIndex + 0.5) / (double) countY;
                        double zFraction = ((double) zIndex + 0.5) / (double) countZ;

                        double particleX = pos.getX() + xFraction * width + minX;
                        double particleY = pos.getY() + yFraction * height + minY;
                        double particleZ = pos.getZ() + zFraction * depth + minZ;

                        TerrainParticle particle = new TerrainParticle(
                                this.level,
                                particleX,
                                particleY,
                                particleZ,
                                xFraction - 0.5,
                                yFraction - 0.5,
                                zFraction - 0.5,
                                state,
                                pos).updateSprite(state, pos);

                        ManagedTerrainParticleState managedState = (ManagedTerrainParticleState) particle;
                        managedState.antikytheramechanism$markParentWorldPath();
                        managedState.antikytheramechanism$markDetachedFromSubLevel();
                        this.add(particle);
                    }
                }
            }
        });
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

            int countX = Math.max(2, Mth.ceil(localWidth * scaleX / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));
            int countY = Math.max(2, Mth.ceil(localHeight * scaleY / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));
            int countZ = Math.max(2, Mth.ceil(localDepth * scaleZ / ANTIKYTHERA_VANILLA_PARTICLE_SPACING));

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

                            ManagedTerrainParticleState managedState = (ManagedTerrainParticleState) particle;
                            managedState.antikytheramechanism$markParentWorldPath();

                            // Sable's add TAIL performs the one and only local -> global projection.
                            this.add(particle);

                            managedState.antikytheramechanism$markDetachedFromSubLevel();
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
                -ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS,
                -ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS,
                -ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS);
        BlockPos max = parentBlockPos.offset(
                ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS,
                ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS,
                ANTIKYTHERA_PARENT_DEBRIS_FRAME_RADIUS);

        for (BlockPos candidate : BlockPos.betweenClosed(min, max)) {
            if (this.level.hasChunkAt(candidate)
                    && this.level.getBlockState(candidate).is(ModRegistries.MECHANISM_FRAME.get())) {
                return true;
            }
        }
        return false;
    }
}
