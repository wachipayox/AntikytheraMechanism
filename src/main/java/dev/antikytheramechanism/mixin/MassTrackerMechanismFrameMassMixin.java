package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps Mechanism Frames in Sable's mass calculation even when their shell collision is hidden.
 *
 * <p>Sable's {@link MassTracker#build} filters blocks through
 * {@link VoxelNeighborhoodState#isSolid} before consulting PhysicsBlockPropertyHelper.getMass().
 * HIDDEN Frames intentionally expose an empty collision shape, so an all-Frame Create contraption
 * is otherwise classified as weightless and MassTracker returns a null center of mass. Sable's
 * Create compatibility dereferences that center during contraption initialization, causing the
 * entity-tick crash.</p>
 *
 * <p>This redirect is deliberately scoped to MassTracker.build only. It does not change Sable's
 * voxel/collision classification, so hidden Frames remain non-colliding; it merely allows the
 * existing ManagedFrameMassPolicy shell mass to participate in mass/inertia calculation.</p>
 */
@Mixin(MassTracker.class)
abstract class MassTrackerMechanismFrameMassMixin {
    @Redirect(
            method = "build",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/physics/chunk/VoxelNeighborhoodState;isSolid(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean antikytheramechanism$countFrameForMass(
            BlockGetter level,
            BlockPos pos,
            BlockState state) {
        if (state.is(ModRegistries.MECHANISM_FRAME.get())) {
            return true;
        }
        return VoxelNeighborhoodState.isSolid(level, pos, state);
    }
}
