package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ClientParticlePerfProbe;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Runs Antikythera terrain debris through Minecraft's ordinary parent-world tick/movement path. */
@Mixin(value = Particle.class, priority = 2000)
abstract class ParticleManagedTerrainTrackingMixin {
    @Unique
    private static final double ANTIKYTHERA_MAX_COLLISION_VELOCITY_SQUARED = 10000.0;

    @Shadow protected ClientLevel level;
    @Shadow protected double xo;
    @Shadow protected double yo;
    @Shadow protected double zo;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;
    @Shadow protected double xd;
    @Shadow protected double yd;
    @Shadow protected double zd;
    @Shadow protected boolean onGround;
    @Shadow protected boolean hasPhysics;
    @Shadow private boolean stoppedByCollision;
    @Shadow protected int age;
    @Shadow protected int lifetime;
    @Shadow protected float gravity;
    @Shadow protected float friction;
    @Shadow protected boolean speedUpWhenYMotionIsBlocked;

    @Shadow public abstract void remove();
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract void setBoundingBox(AABB bb);
    @Shadow protected abstract void setLocationFromBoundingbox();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$tickDetachedDebrisInParentWorld(CallbackInfo callback) {
        if (!((Object) this instanceof ManagedTerrainParticleState state)) {
            return;
        }

        if (state.antikytheramechanism$usesParentWorldPath()
                && !state.antikytheramechanism$isDetachedFromSubLevel()) {
            state.antikytheramechanism$markDetachedFromSubLevel();
        } else if (!state.antikytheramechanism$isDetachedFromSubLevel()) {
            SubLevel tracking = ((ParticleExtension) (Object) this).sable$getTrackingSubLevel();
            if (ManagedClientSubLevelIdentity.isManaged(tracking)) {
                state.antikytheramechanism$markParentWorldPath();
                state.antikytheramechanism$markDetachedFromSubLevel();
            }
        }

        if (!state.antikytheramechanism$isDetachedFromSubLevel()) {
            return;
        }

        long tickStarted = ClientParticlePerfProbe.startTiming();
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            ClientParticlePerfProbe.recordDetachedTick(tickStarted);
            callback.cancel();
            return;
        }

        this.yd -= 0.04 * (double) this.gravity;
        antikytheramechanism$vanillaParentMove(this.xd, this.yd, this.zd);
        if (this.speedUpWhenYMotionIsBlocked && this.y == this.yo) {
            this.xd *= 1.1;
            this.zd *= 1.1;
        }

        this.xd *= (double) this.friction;
        this.yd *= (double) this.friction;
        this.zd *= (double) this.friction;
        if (this.onGround) {
            this.xd *= 0.7F;
            this.zd *= 0.7F;
        }

        ClientParticlePerfProbe.recordDetachedTick(tickStarted);
        callback.cancel();
    }

    @Unique
    private void antikytheramechanism$vanillaParentMove(double motionX, double motionY, double motionZ) {
        if (this.stoppedByCollision) {
            return;
        }

        double requestedX = motionX;
        double requestedY = motionY;
        double requestedZ = motionZ;
        if (this.hasPhysics
                && (motionX != 0.0 || motionY != 0.0 || motionZ != 0.0)
                && motionX * motionX + motionY * motionY + motionZ * motionZ
                        < ANTIKYTHERA_MAX_COLLISION_VELOCITY_SQUARED) {
            long collisionStarted = ClientParticlePerfProbe.startTiming();
            Vec3 collided = Entity.collideBoundingBox(
                    null,
                    new Vec3(motionX, motionY, motionZ),
                    this.getBoundingBox(),
                    this.level,
                    List.of());
            ClientParticlePerfProbe.recordParentCollision(collisionStarted);
            motionX = collided.x;
            motionY = collided.y;
            motionZ = collided.z;
        }

        if (motionX != 0.0 || motionY != 0.0 || motionZ != 0.0) {
            this.setBoundingBox(this.getBoundingBox().move(motionX, motionY, motionZ));
            this.setLocationFromBoundingbox();
        }

        if (Math.abs(requestedY) >= 1.0E-5F && Math.abs(motionY) < 1.0E-5F) {
            this.stoppedByCollision = true;
        }

        this.onGround = requestedY != motionY && requestedY < 0.0;
        if (requestedX != motionX) {
            this.xd = 0.0;
        }
        if (requestedZ != motionZ) {
            this.zd = 0.0;
        }
    }
}
