package dev.antikytheramechanism.sublevel;

import net.minecraft.resources.ResourceLocation;

public final class MiniPhysicsBuiltins {
    public static final ResourceLocation SABLE_FLOATING_MATERIAL =
            ResourceLocation.fromNamespaceAndPath("sable", "floating_material");

    private MiniPhysicsBuiltins() {}

    /**
     * Sable floating material is intentionally not registered as a generic post-impulse transfer.
     * Its prevent-self-lift semantics are nonlinear and must be combined with the host's native
     * floating clusters before Sable computes the global lift cap; HostedMiniFloatingMaterialBridge
     * owns that specialized integration.
     */
    public static void bootstrap() {
    }
}
