package dev.antikytheramechanism.mixin;

import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to Sable's already-maintained floating-material aggregate. */
@Mixin(FloatingBlockController.class)
public interface FloatingBlockControllerAccessor {
    @Accessor("sublevelContainer")
    FloatingClusterContainer antikytheramechanism$getSublevelContainer();

    @Invoker("processBlockChanges")
    void antikytheramechanism$processBlockChanges();
}
