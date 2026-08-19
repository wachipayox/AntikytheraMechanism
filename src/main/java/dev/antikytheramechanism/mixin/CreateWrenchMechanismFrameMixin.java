package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateFrameSkinItems;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create-linked wrench adapter, loaded only while Create is present. */
@Mixin(WrenchItem.class)
abstract class CreateWrenchMechanismFrameMixin {
    private static final IWrenchable FRAME_ROTATOR = new IWrenchable() {};

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void antikytheramechanism$frameWrench(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        Player player = context.getPlayer();
        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        if (player == null || !player.mayBuild() || !(state.getBlock() instanceof MechanismFrameBlock)) {
            return;
        }

        FrameShellMode mode = state.getValue(MechanismFrameBlock.SHELL_MODE);
        if (mode == FrameShellMode.HIDDEN) {
            if (context.getLevel() instanceof ServerLevel serverLevel) {
                MechanismAssemblyManager.get(serverLevel).setFrameShellMode(
                        serverLevel, context.getClickedPos(), FrameShellMode.NORMAL);
            }
            callback.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        if (player.isShiftKeyDown()) {
            if (context.getLevel() instanceof ServerLevel serverLevel) {
                MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
                CreateFrameSkinItems.skinFrom(player.getOffhandItem())
                        .ifPresentOrElse(
                                skin -> manager.setFrameSkin(serverLevel, context.getClickedPos(), skin),
                                () -> manager.cycleFrameShellMode(serverLevel, context.getClickedPos()));
            }
            // Always consume sneak+wrench on a Frame so Create's pickup/removal path is unreachable.
            callback.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        BlockState rotated = FRAME_ROTATOR.getRotatedBlockState(state, context.getClickedFace());
        if (rotated == state
                || rotated.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            callback.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        Direction newFacing = rotated.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (context.getLevel() instanceof ServerLevel serverLevel
                && MechanismAssemblyManager.get(serverLevel).rotateFrame(
                        serverLevel, context.getClickedPos(), newFacing)) {
            IWrenchable.playRotateSound(serverLevel, context.getClickedPos());
        }
        callback.setReturnValue(InteractionResult.SUCCESS);
    }
}
