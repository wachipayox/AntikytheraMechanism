package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Antikythera visibility of the complete synchronous Sable relocation operation. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SubLevelAssemblyMoveContextMixin {
    @WrapMethod(method = "moveBlocks")
    private static void antikythera$withCompleteMoveContext(
            ServerLevel sourceLevel,
            AssemblyTransform transform,
            Iterable<BlockPos> blocks,
            Operation<Void> original) {
        SableAssemblyMoveContext.begin(sourceLevel, transform, blocks);
        try {
            original.call(sourceLevel, transform, blocks);
        } finally {
            SableAssemblyMoveContext.end();
        }
    }
}
