package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.PhysicsStaffServerSelectionBridge;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.UUID;

@Mixin(PhysicsStaffServerHandler.class)
abstract class PhysicsStaffServerHandlerAntikytheraMixin {
    @Shadow private ServerLevel level;

    @WrapMethod(method = "toggleLock")
    private void antikytheramechanism$toggleHost(UUID selectedId, Operation<Void> original) {
        PhysicsStaffServerSelectionBridge.Selection remapped =
                PhysicsStaffServerSelectionBridge.resolveManaged(this.level, selectedId);
        if (remapped == null) {
            original.call(selectedId);
        } else if (remapped.hasHost()) {
            original.call(remapped.host().getUniqueId());
        }
    }

    @WrapMethod(method = "drag")
    private void antikytheramechanism$dragHost(
            UUID playerId,
            UUID selectedId,
            Vector3dc globalAnchor,
            Vector3dc localAnchor,
            Quaterniondc orientation,
            Operation<Void> original) {
        PhysicsStaffServerSelectionBridge.Selection remapped =
                PhysicsStaffServerSelectionBridge.resolveManaged(this.level, selectedId);
        if (remapped == null) {
            original.call(playerId, selectedId, globalAnchor, localAnchor, orientation);
            return;
        }
        if (!remapped.hasHost()) {
            return;
        }
        original.call(
                playerId,
                remapped.host().getUniqueId(),
                globalAnchor,
                remapped.framePivot(localAnchor),
                new Quaterniond(remapped.host().logicalPose().orientation()));
    }
}
