package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.interaction.AuthoritativePlacementSound;
import dev.antikytheramechanism.interaction.ManagedPlacementCollisionPolicy;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
abstract class BlockItemMiniEnvironmentMixin {
    /**
     * Sable injects a scale-unaware full-block OBB veto inside BlockPlaceContext#canPlace. Intercept
     * one level above it so Frame children can keep their reserved-volume bypass while detached
     * half-scale bodies use Antikythera's scale-correct cross-level collision test.
     */
    @WrapOperation(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/context/BlockPlaceContext;canPlace()Z"))
    private boolean antikytheramechanism$useCorrectedPlacementEligibility(
            BlockPlaceContext context,
            Operation<Boolean> original) {
        if (!ManagedPlacementCollisionPolicy.shouldUseVanillaContextCanPlace(context)) {
            return original.call(context);
        }
        return ManagedPlacementCollisionPolicy.correctedContextCanPlace(context);
    }

    /**
     * Vanilla excludes the placing player from the server-side BlockItem placement sound because the
     * normal client placement path has already played it predictively. Antikythera's authoritative
     * routes do not necessarily have a usable client SubLevel yet, so broadcast the same sound once
     * from the physical/world position and include the placing player. This also avoids relying on a
     * second Sable plot lookup for a Frame that itself lives in a foreign SubLevel.
     */
    @WrapOperation(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void antikytheramechanism$includePlayerInAuthoritativePlacementSound(
            Level level,
            @Nullable Player excludedPlayer,
            BlockPos pos,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch,
            Operation<Void> original) {
        if (!AuthoritativePlacementSound.shouldIncludePlacingPlayer()) {
            original.call(level, excludedPlayer, pos, sound, source, volume, pitch);
            return;
        }

        Vec3 physical = AuthoritativePlacementSound.physicalSoundPosition(level, pos);
        level.playSound(null, physical.x, physical.y, physical.z, sound, source, volume, pitch);
    }

    /**
     * Some redstone blocks perform their initial powered-state check from Block#setPlacedBy rather
     * than getStateForPlacement/canSurvive. Keep the projected parent shell visible for that final
     * placement callback too; otherwise a repeater/piston placed after an already-powered macro
     * neighbour stays stale until the macro source changes again.
     */
    @WrapOperation(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void antikytheramechanism$readVirtualBoundaryDuringSetPlacedBy(
            Block block,
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack,
            Operation<Void> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(level, pos)) {
            original.call(block, level, pos, state, placer, stack);
            return;
        }
        MiniWorldEnvironment.withVirtualReads(
                () -> original.call(block, level, pos, state, placer, stack));
    }

    @WrapMethod(method = "getPlacementState")
    private @Nullable BlockState antikytheramechanism$readVirtualSupportForPlacementState(
            BlockPlaceContext context,
            Operation<BlockState> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(context.getLevel(), context.getClickedPos())) {
            return original.call(context);
        }
        return MiniWorldEnvironment.withVirtualReads(() -> original.call(context));
    }

    @WrapMethod(method = "canPlace")
    private boolean antikytheramechanism$readVirtualSupportForSurvival(
            BlockPlaceContext context,
            BlockState state,
            Operation<Boolean> original) {
        if (!MiniWorldEnvironment.shouldUseVirtualReads(context.getLevel(), context.getClickedPos())) {
            return original.call(context, state);
        }
        return MiniWorldEnvironment.withVirtualReads(() -> original.call(context, state));
    }
}
