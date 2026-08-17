package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Create's normal dependent-subtree repair without reimplementing its kinetic algorithm. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
public interface CreateRotationPropagatorAccessor {
    @Invoker("propagateMissingSource")
    static void antikytheramechanism$propagateMissingSource(KineticBlockEntity updateBlockEntity) {
        throw new AssertionError();
    }
}
