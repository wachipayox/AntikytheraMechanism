package dev.antikytheramechanism.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the small pieces of Create's kinetic propagation algorithm required by virtual bridges. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
public interface CreateRotationPropagatorAccessor {
    @Invoker("propagateMissingSource")
    static void antikytheramechanism$propagateMissingSource(KineticBlockEntity updateBlockEntity) {
        throw new AssertionError();
    }

    /**
     * Native port modifier used by GearboxBlockEntity and SplitShaftBlockEntity. Calling Create's
     * implementation keeps virtual Frame-boundary shafts semantically identical to ordinary
     * macro kinetic neighbours, including horizontal/vertical gearboxes.
     */
    @Invoker("getAxisModifier")
    static float antikytheramechanism$getAxisModifier(KineticBlockEntity blockEntity, Direction direction) {
        throw new AssertionError();
    }
}
