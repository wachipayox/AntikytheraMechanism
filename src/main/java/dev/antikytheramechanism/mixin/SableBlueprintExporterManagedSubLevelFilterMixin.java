package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession;
import dev.rew1nd.sableschematicapi.api.blueprint.SubLevelSaveFrame;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents Antikythera's internal mini-content SubLevels from becoming standalone Photomancy
 * blueprint bodies. Frames themselves remain ordinary blocks of their physical host and transport
 * their mini contents through PortableFrameContent.
 */
@Mixin(value = SableBlueprintExporter.class, remap = false)
public abstract class SableBlueprintExporterManagedSubLevelFilterMixin {
    @Redirect(
            method = "export(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Ldev/ryanhcode/sable/companion/math/BoundingBox3d;Ljava/lang/Iterable;)Ldev/rew1nd/sableschematicapi/blueprint/SableBlueprint;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/rew1nd/sableschematicapi/api/blueprint/BlueprintSaveSession;addFrame(Ldev/rew1nd/sableschematicapi/api/blueprint/SubLevelSaveFrame;)V"))
    private static void antikytheramechanism$skipManagedMiniSubLevel(
            BlueprintSaveSession session,
            SubLevelSaveFrame frame) {
        if (MechanismSubLevelService.getOwnerAssemblyId(frame.subLevel()) != null) {
            AntikytheraMechanism.LOGGER.debug(
                    "Excluded Antikythera-managed mini SubLevel {} from Sable Photomancy export",
                    frame.sourceUuid());
            return;
        }
        session.addFrame(frame);
    }
}
