package dev.antikytheramechanism.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the native Create placement-helper registration owned by each CogwheelBlockItem. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.simpleRelays.CogwheelBlockItem", remap = false)
public interface CreateCogwheelBlockItemPlacementAccessor {
    @Accessor("placementHelperId")
    int antikytheramechanism$getPlacementHelperId();
}
