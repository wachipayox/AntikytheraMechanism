package dev.antikytheramechanism.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.client.PhysicsStaffClientSelectionBridge;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffAction;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(PhysicsStaffClientHandler.class)
abstract class PhysicsStaffClientHandlerAntikytheraMixin {
    @Unique private @Nullable PhysicsStaffClientSelectionBridge.Selection antikytheramechanism$selection;

    @Inject(method = "onItemUsed", at = @At("HEAD"))
    private void antikytheramechanism$begin(PhysicsStaffAction action, CallbackInfo ci) {
        this.antikytheramechanism$selection = null;
    }

    @Inject(method = "onItemUsed", at = @At("RETURN"))
    private void antikytheramechanism$finish(PhysicsStaffAction action, CallbackInfo ci) {
        this.antikytheramechanism$selection = null;
    }

    @WrapOperation(method = "onItemUsed", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContainingClient(Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;"))
    private ClientSubLevel antikytheramechanism$selectHost(
            ActiveSableCompanion helper, Position hit, Operation<ClientSubLevel> original) {
        ClientSubLevel selected = original.call(helper, hit);
        if (!ManagedClientSubLevelIdentity.isManaged(selected)) return selected;
        PhysicsStaffClientSelectionBridge.Selection remapped = PhysicsStaffClientSelectionBridge.resolve(selected, hit);
        this.antikytheramechanism$selection = remapped;
        return remapped != null ? remapped.host() : null;
    }

    @WrapOperation(method = "onItemUsed", at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler;startDraggingSubLevel(Ldev/ryanhcode/sable/sublevel/SubLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;)V"))
    private void antikytheramechanism$dragHost(
            PhysicsStaffClientHandler handler, SubLevel selected, BlockPos blockPos,
            LocalPlayer player, InteractionHand hand, Operation<Void> original) {
        PhysicsStaffClientSelectionBridge.Selection remapped = this.antikytheramechanism$selection;
        if (remapped != null) {
            original.call(handler, remapped.host(), remapped.frameForMiniBlock(blockPos), player, hand);
        } else {
            original.call(handler, selected, blockPos, player, hand);
        }
    }

    @WrapOperation(method = "onItemUsed", at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler;lockSubLevel(Ldev/ryanhcode/sable/sublevel/SubLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;)V"))
    private void antikytheramechanism$lockHost(
            PhysicsStaffClientHandler handler, SubLevel selected, Vec3 hit,
            LocalPlayer player, InteractionHand hand, Operation<Void> original) {
        PhysicsStaffClientSelectionBridge.Selection remapped = this.antikytheramechanism$selection;
        if (remapped != null) original.call(handler, remapped.host(), remapped.hostHitLocation(), player, hand);
        else original.call(handler, selected, hit, player, hand);
    }

    @WrapOperation(method = "onItemUsed", at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler;updateBeam(Lnet/minecraft/world/level/Level;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"))
    private void antikytheramechanism$beamToHost(
            PhysicsStaffClientHandler handler, Level level, UUID playerId, Vec3 start, Vec3 end,
            Operation<Void> original) {
        PhysicsStaffClientSelectionBridge.Selection remapped = this.antikytheramechanism$selection;
        original.call(handler, level, playerId, start, remapped != null ? remapped.hostHitLocation() : end);
    }

    @WrapOperation(method = "onItemUsed", at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler;spawnParticles(Lnet/minecraft/world/InteractionHand;Ldev/ryanhcode/sable/sublevel/SubLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;)V"))
    private void antikytheramechanism$particlesAtHost(
            InteractionHand hand, SubLevel selected, Vec3 hit, Level level, Operation<Void> original) {
        PhysicsStaffClientSelectionBridge.Selection remapped = this.antikytheramechanism$selection;
        if (remapped != null) original.call(hand, remapped.host(), remapped.hostHitLocation(), level);
        else original.call(hand, selected, hit, level);
    }
}
