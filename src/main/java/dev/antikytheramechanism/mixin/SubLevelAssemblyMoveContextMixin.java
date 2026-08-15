package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.sublevel.SableAssemblyMoveContext;
import dev.antikytheramechanism.sublevel.SableFrameRelocationService;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.List;

/** Gives Antikythera visibility of the complete synchronous Sable relocation operation. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SubLevelAssemblyMoveContextMixin {
    @WrapMethod(method = "moveBlocks")
    private static void antikythera$withCompleteMoveContext(
            ServerLevel sourceLevel,
            AssemblyTransform transform,
            Iterable<BlockPos> blocks,
            Operation<Void> original) {
        // moveBlocks itself iterates this collection several times. Materialize it once before the
        // Antikythera pre-pass so our context never consumes a caller-supplied lazy iterable before
        // Sable sees it, and freeze positions against mutable BlockPos implementations.
        List<BlockPos> movedBlocks = new ArrayList<>();
        for (BlockPos block : blocks) {
            movedBlocks.add(block.immutable());
        }

        SableAssemblyMoveContext.begin(sourceLevel, transform, movedBlocks);
        boolean completed = false;
        try {
            // A Sable host split can move only a strict subset of the Frames that currently share
            // one Antikythera child. Partition that logical assembly while the complete coherent
            // source state is still present, before Sable invokes the first per-block listener.
            if (!SableFrameRelocationService.prepareMoveOperation(sourceLevel, movedBlocks)) {
                throw new IllegalStateException(
                        "Antikythera could not safely partition a partial Frame assembly for Sable moveBlocks");
            }

            original.call(sourceLevel, transform, movedBlocks);
            completed = true;
        } finally {
            try {
                // A Frame can be copied before another macro block in the same Sable move. Keep the
                // relocation journal (and therefore its frozen structural boundary snapshot) alive
                // until Sable has copied, notified and removed the complete source set. If Sable
                // throws, leave the persisted journal fail-closed for recovery instead of committing
                // a partially moved assembly.
                if (completed) {
                    SableFrameRelocationService.finishMoveOperation(sourceLevel);
                }
            } finally {
                SableAssemblyMoveContext.end();
            }
        }
    }
}
