package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.frame.FramePresentationToolHooks;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hidden Frames remain wrench-addressable without stealing every ray that passes through their cube.
 * The maintenance pick volume follows only the twelve visible wireframe edges; the real collision
 * shape remains empty in MechanismFrameBlock.
 */
@Mixin(MechanismFrameBlock.class)
abstract class MechanismFrameHiddenSelectionMixin {
    private static final double PICK_BAR = 1.0;
    private static final VoxelShape EDGE_PICK_SHAPE = buildEdgePickShape();

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$targetOnlyHiddenFrameEdges(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> callback) {
        if (state.getValue(MechanismFrameBlock.SHELL_MODE) != FrameShellMode.HIDDEN
                || !(context instanceof EntityCollisionContext entityContext)
                || !(entityContext.getEntity() instanceof Player player)
                || !FramePresentationToolHooks.isMaintenanceTool(player.getMainHandItem())) {
            return;
        }
        callback.setReturnValue(EDGE_PICK_SHAPE);
    }

    private static VoxelShape buildEdgePickShape() {
        VoxelShape result = Shapes.empty();
        double low = PICK_BAR;
        double high = 16.0 - PICK_BAR;

        // X edges.
        for (double y : new double[] {0.0, high}) {
            for (double z : new double[] {0.0, high}) {
                result = Shapes.or(result, Block.box(0.0, y, z, 16.0, y + low, z + low));
            }
        }
        // Y edges.
        for (double x : new double[] {0.0, high}) {
            for (double z : new double[] {0.0, high}) {
                result = Shapes.or(result, Block.box(x, 0.0, z, x + low, 16.0, z + low));
            }
        }
        // Z edges.
        for (double x : new double[] {0.0, high}) {
            for (double y : new double[] {0.0, high}) {
                result = Shapes.or(result, Block.box(x, y, 0.0, x + low, y + low, 16.0));
            }
        }
        return result.optimize();
    }
}
