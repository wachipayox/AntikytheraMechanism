package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.api.physics.MiniPhysicsEffectRegistry;
import net.minecraft.resources.ResourceLocation;

public final class MiniPhysicsBuiltins {
    public static final ResourceLocation SABLE_FLOATING_MATERIAL =
            ResourceLocation.fromNamespaceAndPath("sable", "floating_material");

    private MiniPhysicsBuiltins() {}

    public static void bootstrap() {
        MiniPhysicsEffectRegistry.registerVolumeScaled(SABLE_FLOATING_MATERIAL);
    }
}
