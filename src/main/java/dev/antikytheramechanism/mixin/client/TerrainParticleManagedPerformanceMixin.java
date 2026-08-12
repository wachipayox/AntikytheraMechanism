package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns Antikythera block debris into ordinary parent-world particles immediately after the
 * TerrainParticle constructor finishes.
 *
 * <p>Legacy/local creation paths may still construct TerrainParticles in plot coordinates. Those
 * are projected exactly once here. The managed ParticleEngine destroy path now creates correctly
 * scaled debris directly in parent-world coordinates and calls {@link
 * ManagedTerrainParticleState#antikytheramechanism$markDetachedFromSubLevel()} explicitly instead.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin extends Particle
        implements ParticleSubLevelKickable, ManagedTerrainParticleState {
    @Unique
    private static final double ANTIKYTHERA_LIGHT_QUERY_AREA = 8.0;

    @Shadow
    @Final
    private BlockPos pos;

    @Unique
    private boolean antikytheramechanism$detachedFromSubLevel;

    protected TerrainParticleManagedPerformanceMixin(
            ClientLevel level,
            double x,
            double y,
            double z) {
        super(level, x, y, z);
    }

    @Inject(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("TAIL"))
    private void antikytheramechanism$projectManagedDebrisAtBirth(
            ClientLevel level,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            BlockState state,
            BlockPos sourcePos,
            CallbackInfo callback) {
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(this.pos);
        if (!MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return;
        }

        Vec3 globalPosition = subLevel.logicalPose().transformPosition(new Vec3(this.x, this.y, this.z));
        Vec3 globalPrevious = subLevel.logicalPose().transformPosition(new Vec3(this.xo, this.yo, this.zo));
        Vec3 globalVelocity = subLevel.logicalPose().transformNormal(new Vec3(this.xd, this.yd, this.zd));

        this.x = globalPosition.x;
        this.y = globalPosition.y;
        this.z = globalPosition.z;
        this.xo = globalPrevious.x;
        this.yo = globalPrevious.y;
        this.zo = globalPrevious.z;
        this.xd = globalVelocity.x;
        this.yd = globalVelocity.y;
        this.zd = globalVelocity.z;
        this.setPos(this.x, this.y, this.z);
        this.antikytheramechanism$markDetachedFromSubLevel();
    }

    @Override
    public boolean antikytheramechanism$isDetachedFromSubLevel() {
        return this.antikytheramechanism$detachedFromSubLevel;
    }

    @Override
    public void antikytheramechanism$markDetachedFromSubLevel() {
        ((ParticleExtension) (Object) this).sable$setTrackingSubLevel(
                null,
                new Vec3(this.x, this.y, this.z));
        this.antikytheramechanism$detachedFromSubLevel = true;
    }

    @Override
    public boolean sable$shouldCareAboutIntersectingSubLevels() {
        if (this.antikytheramechanism$detachedFromSubLevel) {
            return false;
        }
        return !antikytheramechanism$intersectsOnlyManagedSubLevels(0.5);
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !this.antikytheramechanism$detachedFromSubLevel;
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !this.antikytheramechanism$detachedFromSubLevel;
    }

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useParentWorldLight(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        if (this.antikytheramechanism$detachedFromSubLevel) {
            BlockPos currentPos = BlockPos.containing(this.x, this.y, this.z);
            callback.setReturnValue(this.level.hasChunkAt(currentPos)
                    ? LevelRenderer.getLightColor(this.level, currentPos)
                    : 0);
            return;
        }

        if (!antikytheramechanism$intersectsOnlyManagedSubLevels(ANTIKYTHERA_LIGHT_QUERY_AREA)) {
            return;
        }

        BlockPos currentPos = BlockPos.containing(this.x, this.y, this.z);
        callback.setReturnValue(this.level.hasChunkAt(currentPos)
                ? LevelRenderer.getLightColor(this.level, currentPos)
                : 0);
    }

    @Unique
    private boolean antikytheramechanism$intersectsOnlyManagedSubLevels(double expansion) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        BoundingBox3d queryBounds;
        if (expansion >= ANTIKYTHERA_LIGHT_QUERY_AREA) {
            BlockPos particlePos = BlockPos.containing(this.x, this.y, this.z);
            queryBounds = new BoundingBox3d(particlePos).expand(expansion);
        } else {
            queryBounds = new BoundingBox3d(this.getBoundingBox()).expand(expansion);
        }

        boolean foundManaged = false;
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(minecraft.level, queryBounds)) {
            if (MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
                foundManaged = true;
            } else {
                return false;
            }
        }
        return foundManaged;
    }
}
