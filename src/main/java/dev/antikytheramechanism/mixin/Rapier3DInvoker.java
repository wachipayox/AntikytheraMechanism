package dev.antikytheramechanism.mixin;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Narrow bridge to the Rapier operations that Sable 2.0.3 does not expose through PhysicsPipeline.
 *
 * <p>The target is deliberately named as a string because {@code sable_rapier} is shipped by Sable
 * as a jar-in-jar runtime library and is therefore absent from a consumer's compile classpath. The
 * signatures below are pinned to and verified against Sable tag {@code mc1.21.1-2.0.3-neoforge}.
 * Keeping the dependency here prevents normal Antikythera code from importing Sable's internal
 * Rapier implementation while still letting the hosted collision adapter use the exact backend that
 * Sable itself loaded.</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false)
public interface Rapier3DInvoker {
    @Invoker("getSceneHandle")
    static long antikytheramechanism$getSceneHandle(ServerLevel level) {
        throw new AssertionError();
    }

    @Invoker("newVoxelCollider")
    static int antikytheramechanism$newVoxelCollider(
            double frictionMultiplier,
            double volume,
            double restitution,
            boolean isFluid,
            BlockSubLevelCollisionCallback contactEvents) {
        throw new AssertionError();
    }

    @Invoker("addVoxelColliderBox")
    static void antikytheramechanism$addVoxelColliderBox(int index, double[] bounds) {
        throw new AssertionError();
    }

    @Invoker("createKinematicContraption")
    static void antikytheramechanism$createKinematicContraption(
            long sceneHandle,
            int mountId,
            int id,
            double[] pose) {
        throw new AssertionError();
    }

    @Invoker("removeKinematicContraption")
    static void antikytheramechanism$removeKinematicContraption(long sceneHandle, int id) {
        throw new AssertionError();
    }

    @Invoker("setKinematicContraptionTransform")
    static void antikytheramechanism$setKinematicContraptionTransform(
            long sceneHandle,
            int id,
            double[] centerOfMass,
            double[] pose,
            double[] velocities) {
        throw new AssertionError();
    }

    @Invoker("addKinematicContraptionChunkSection")
    static void antikytheramechanism$addKinematicContraptionChunkSection(
            long sceneHandle,
            int id,
            int x,
            int y,
            int z,
            int[] data) {
        throw new AssertionError();
    }

    @Invoker("setLocalBounds")
    static void antikytheramechanism$setLocalBounds(
            long sceneHandle,
            int id,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        throw new AssertionError();
    }
}
