package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.sublevel.ManagedRapierBounds;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/** Makes persisted Antikythera ownership visible throughout Sable's plot bootstrap. */
@Mixin(value = SubLevelSerializer.class, priority = 2000, remap = false)
abstract class SubLevelSerializerManagedLoadMixin {
    @WrapOperation(
            method = "fullyLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;allocateSubLevel(Ljava/util/UUID;IILdev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/sublevel/SubLevel;"))
    private static SubLevel antikytheramechanism$restoreIdentityBeforePlotLoad(
            ServerSubLevelContainer container,
            UUID uuid,
            int plotX,
            int plotZ,
            Pose3d pose,
            Operation<SubLevel> original,
            ServerLevel level,
            SubLevelData serialized) {
        SubLevel allocated = original.call(container, uuid, plotX, plotZ, pose);
        if (allocated instanceof ServerSubLevel serverSubLevel) {
            MechanismSubLevelService.restoreOwnershipBeforePlotLoad(
                    serverSubLevel,
                    serialized.fullTag());
        }
        return allocated;
    }

    /**
     * Sable rejects EMPTY after plot loading even though a frame-owned empty mechanism is valid.
     * Return a finite value only to that validation code; the plot keeps its real EMPTY bounds.
     */
    @WrapOperation(
            method = "fullyLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/plot/ServerLevelPlot;getBoundingBox()Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;"))
    private static BoundingBox3ic antikytheramechanism$acceptManagedEmptyPlot(
            ServerLevelPlot plot,
            Operation<BoundingBox3ic> original) {
        BoundingBox3ic bounds = original.call(plot);
        if (MiniWorldEnvironment.isManagedSubLevel(plot.getSubLevel())
                && ManagedRapierBounds.isEmptySentinel(bounds)) {
            return new BoundingBox3i(0, 0, 0, 0, 0, 0);
        }
        return bounds;
    }
}
