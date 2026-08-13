package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.client.ManagedMiniParticleSpawnContext;
import dev.antikytheramechanism.client.ManagedTerrainParticleState;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
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
 * Classifies Antikythera terrain debris once and keeps its light/movement in parent-world space.
 *
 * <p>Spatial proximity to a Mechanism Frame is intentionally not discovered here. ParticleEngine is
 * the operation-level owner of macro destroy/crack effects and performs that test once before particle
 * creation. Doing a 3-block cube scan from every TerrainParticle multiplied one block break into tens
 * of thousands of BlockState reads and could itself become the FPS spike we were trying to remove.</p>
 */
@Mixin(TerrainParticle.class)
abstract class TerrainParticleManagedPerformanceMixin extends Particle
        implements ParticleSubLevelKickable, ManagedTerrainParticleState {
    @Shadow
    @Final
    private BlockPos pos;

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

        boolean managedSource = managedDestroy || ManagedClientSubLevelIdentity.isManaged(sourceSubLevel);
        if (parentDebris || managedSource) {
            this.antikytheramechanism$markParentWorldPath();
        }

        // Parent-world destroy/crack effects are already global. Their ParticleEngine operation is
        // responsible for setting the parent-debris context before construction.
        if (parentDebris) {
            this.antikytheramechanism$markDetachedFromSubLevel();
            return;
        }

        // The optimized mini destroy path is still in plot coordinates here. ParticleEngine#add must
        // perform Sable's single official local -> global kick-out before the caller detaches it.
        if (managedDestroy) {
            return;
        }

        // Crack/hit effects whose sourcePos is the managed plot coordinate do not pass through the
        // optimized destroy context. Project them exactly once, then detach.
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

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useParentLightBeforeSable(
            float partialTick,
            CallbackInfoReturnable<Integer> callback) {
        if (!this.antikytheramechanism$parentWorldPath) {
            // O(1) plot lookup fallback for managed mini-source particles. TerrainParticle retains
            // the original source BlockPos even after its visible coordinates have been projected.
            ClientSubLevel sourceSubLevel = Sable.HELPER.getContainingClient(this.pos);
            if (ManagedClientSubLevelIdentity.isManaged(sourceSubLevel)) {
                this.antikytheramechanism$markParentWorldPath();
            }
        }

        if (!this.antikytheramechanism$parentWorldPath) {
            return;
        }

        BlockPos currentPos = BlockPos.containing(this.x, this.y, this.z);
        callback.setReturnValue(this.level.hasChunkAt(currentPos)
                ? LevelRenderer.getLightColor(this.level, currentPos)
                : 0);
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
