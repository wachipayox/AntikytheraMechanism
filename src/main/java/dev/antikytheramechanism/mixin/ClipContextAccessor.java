package dev.antikytheramechanism.mixin;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClipContext.class)
public interface ClipContextAccessor {
    @Accessor("block")
    ClipContext.Block antikytheramechanism$getBlockMode();

    @Accessor("fluid")
    ClipContext.Fluid antikytheramechanism$getFluidMode();

    @Accessor("collisionContext")
    CollisionContext antikytheramechanism$getCollisionContext();
}
