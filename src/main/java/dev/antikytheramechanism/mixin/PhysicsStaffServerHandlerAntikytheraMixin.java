package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.compat.simulated.PhysicsStaffLockDiagnostics;
import dev.antikytheramechanism.sublevel.PhysicsStaffServerSelectionBridge;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
            this.antikytheramechanism$toggleWithDiagnostics(selectedId, "direct", original);
        } else if (remapped.hasHost()) {
            this.antikytheramechanism$toggleWithDiagnostics(
                    remapped.host().getUniqueId(),
                    "managed-host:" + selectedId,
                    original);
        }
    }

    private void antikytheramechanism$toggleWithDiagnostics(
            UUID targetId,
            String route,
            Operation<Void> original) {
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(this.level);
        SubLevel candidate = container != null ? container.getSubLevel(targetId) : null;
        ServerSubLevel target = candidate instanceof ServerSubLevel serverSubLevel
                ? serverSubLevel
                : null;
        PhysicsStaffServerHandler handler = (PhysicsStaffServerHandler) (Object) this;
        boolean wasLocked = target != null && handler.isLocked(target);
        boolean trace = target != null && !wasLocked && PhysicsStaffLockDiagnostics.isScaled(target);

        if (trace) {
            PhysicsStaffLockDiagnostics.begin(route, target);
        }

        original.call(targetId);

        if (trace) {
            if (handler.isLocked(target)) {
                PhysicsStaffLockDiagnostics.immediate(route, target);
            } else {
                PhysicsStaffLockDiagnostics.cancel(target);
            }
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
