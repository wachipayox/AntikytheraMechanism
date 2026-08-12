package dev.antikytheramechanism.mixin;

import net.minecraft.world.level.block.RedStoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes vanilla's temporary redstone-wire signal suppression to the boundary bridge. */
@Mixin(RedStoneWireBlock.class)
public interface RedStoneWireBlockAccessor {
    @Accessor("shouldSignal")
    boolean antikytheramechanism$shouldSignal();
}
