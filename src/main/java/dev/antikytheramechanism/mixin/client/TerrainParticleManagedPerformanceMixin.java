package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedMiniParticleSpawnContext;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Classifies Antikythera terrain debris once, at construction time, and keeps it out of Sable's
 * transformed particle work after the one intentional mini-to-world projection.
 *
 * <p>The important distinction is origin, not current tracking state. A mini destruction particle
 * can lose or reacquire Sable tracking while the block removal is changing plot bounds, and an empty
 * plot makes Sable's generic light path especially expensive. Antikythera therefore records a
 * permanent parent-world-path bit as soon as the TerrainParticle is constructed.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin extends Particle
        implements ParticleSubLevelKickable, ManagedTerrainParticleState {
    @Unique
    private boolean antikytheramechanism$detachedFromSubLevel;

    @Unique
    private boolean antikytheramechanism$parentWorldPath;

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
    private void antikytheramechanism$classifyManagedDebrisAtBirth(
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
        boolean parentDebris = ManagedMiniParticleSpawnContext.shouldDetachParentTerrainParticles();
        boolean managedDestroy = ManagedMiniParticleSpawnContext.isDeferringToSableKickOut();

        ClientSubLevel sourceSubLevel = null;
        if (!parentDebris && !managedDestroy) {
            sourceSubLevel = Sable.HELPER.getContainingClient(sourcePos);
        }
        boolean managedSource = managedDestroy || MiniWorldEnvironment.isManagedSubLevel(sourceSubLevel);

        if (parentDebris || managedSource) {
            this.antikytheramechanism$markParentWorldPath();
        }

        // Parent-world destroy effects are already global. Detach immediately so Sable never starts
        // transformed collision/tracking work for debris merely flying through a Mechanism Frame.
        if (parentDebris) {
            this.antikytheramechanism$markDetachedFromSubLevel();
            return;
        }

        // The optimized mini destroy path is still in plot coordinates here. ParticleEngine#add must
        // perform Sable's single official local -> global kick-out before the caller detaches it.
        if (managedDestroy) {
            return;
        }

        // Crack/hit effects and other TerrainParticles created directly from a managed mini source do
        // not pass through the optimized destroy context. Project them now, then permanently detach.
        if (!managedSource || sourceSubLevel == null) {
            return;
        }

        Vec3 globalPosition = sourceSubLevel.logicalPose().transformPosition(new Vec3(this.x, this.y, this.z));
        Vec3 globalPrevious = sourceSubLevel.logicalPose().transformPosition(new Vec3(this.xo, this.yo, this.zo));
        Vec3 globalVelocity = sourceSubLevel.logicalPose().transformNormal(new Vec3(this.xd, this.yd, this.zd));

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
    public boolean antikytheramechanism$usesParentWorldPath() {
        return this.antikytheramechanism$parentWorldPath;
    }

    @Override
    public void antikytheramechanism$markParentWorldPath() {
        this.antikytheramechanism$parentWorldPath = true;
    }

    @Override
    public void antikytheramechanism$markDetachedFromSubLevel() {
        this.antikytheramechanism$parentWorldPath = true;
        ((ParticleExtension) (Object) this).sable$setTrackingSubLevel(
                null,
                new Vec3(this.x, this.y, this.z));
        this.antikytheramechanism$detachedFromSubLevel = true;
    }

    @Override
    public boolean sable$shouldCareAboutIntersectingSubLevels() {
        return !this.antikytheramechanism$parentWorldPath;
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !this.antikytheramechanism$parentWorldPath;
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !this.antikytheramechanism$parentWorldPath;
    }
}
