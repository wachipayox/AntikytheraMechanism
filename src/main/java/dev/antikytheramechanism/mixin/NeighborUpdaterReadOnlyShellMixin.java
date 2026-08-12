package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Antikythera's projected parent-world shell strictly read-only.
 *
 * <p>Managed mini lifecycle callbacks run inside MiniWorldEnvironment.withVirtualReads so support,
 * redstone signal and other ordinary block-state queries can see the directly adjacent real world.
 * Nested neighbor propagation must not, however, execute lifecycle on the projected shell itself.
 * Vanilla NeighborUpdater reads the target state again and then calls updateShape/neighborChanged;
 * if that read returns a projected redstone wire/repeater, vanilla may remove/drop/update a block
 * that does not physically exist at the plot coordinate, producing reconnect/drop loops and dupes.
 *
 * <p>Cancel only when virtualBlockState resolves the target. Owned mini positions return null and
 * continue normally. Outside a virtual-read scope virtualBlockState also returns null, so ordinary
 * Sable/vanilla neighbor processing is untouched.</p>
 */
@Mixin(NeighborUpdater.class)
abstract class NeighborUpdaterReadOnlyShellMixin {
    @Inject(method = "executeUpdate", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$skipProjectedShellNeighborLifecycle(
            Level level,
            BlockState state,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston,
            CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && MiniWorldEnvironment.virtualBlockState(serverLevel, pos) != null) {
            callback.cancel();
        }
    }

    @Inject(method = "executeShapeUpdate", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$skipProjectedShellShapeLifecycle(
            LevelAccessor level,
            Direction direction,
            BlockState state,
            BlockPos pos,
            BlockPos neighborPos,
            int flags,
            int recursionLevel,
            CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && MiniWorldEnvironment.virtualBlockState(serverLevel, pos) != null) {
            callback.cancel();
        }
    }
}
