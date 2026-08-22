package dev.antikytheramechanism.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Keeps Create's persisted lastStressApplied cache coherent after dynamic propeller stress changes. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public interface CreateKineticBlockEntityStressAccessor {
    @Accessor("lastStressApplied")
    void antikytheramechanism$setLastStressApplied(float stress);
}
