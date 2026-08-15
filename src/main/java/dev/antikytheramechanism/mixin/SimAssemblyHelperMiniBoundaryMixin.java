package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.mixin.accessor.ControlledContraptionEntityAccessor;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;

/**
 * Closes Simulated's assembly and sublevel-disassembly paths before they can mutate incompatible
 * Antikythera mini bodies.
 */
@Mixin(value = SimAssemblyHelper.class, remap = false)
abstract class SimAssemblyHelperMiniBoundaryMixin {
    /**
     * Last-resort guard for Simulated merging glue. MergingGlueBlockEntity removes its temporary glue
     * pair before calling disassembleSubLevel, so cancelling at HEAD leaves both source bodies intact
     * and detached rather than entering Sable's cross-scale moveBlocks path.
     */
    @Inject(method = "disassembleSubLevel", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$rejectMixedScaleSubLevelDisassembly(
            Level level,
            SubLevel toDisassemble,
            BlockPos subLevelAnchor,
            BlockPos disassemblyGoal,
            Rotation rotation,
            boolean playSound,
            CallbackInfo callback) {
        SubLevel destination = Sable.HELPER.getContaining(level, disassemblyGoal);
        if (DetachedMiniPhysicsSubLevelService.canMergeWithDetached(toDisassemble, destination)) {
            return;
        }

        AntikytheraMechanism.LOGGER.warn(
                "Rejected Simulated SubLevel disassembly from detached Antikythera body {} into incompatible body {}",
                toDisassemble == null ? "<root>" : toDisassemble.getUniqueId(),
                destination == null ? "<root>" : destination.getUniqueId());
        callback.cancel();
    }

    @WrapOperation(
            method = "assembleFromSingleBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/util/SimAssemblyHelper;disassembleAndAddCreateContraptions(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;Ljava/util/Collection;ZLjava/util/List;)V"))
    private static void antikytheramechanism$rejectUncheckedCreateExpansion(
            Level level,
            BoundingBox3ic assemblyBounds,
            Collection<BlockPos> blocks,
            boolean passGluesBack,
            List<AABB> collectedGlues,
            Operation<Void> original) throws AssemblyException {
        boolean detachedSelected = containsDetached(level, blocks);
        if (detachedSelected) {
            BlockPos incompatible = firstNonHalfScalePosition(level, blocks);
            if (incompatible != null) {
                throw AssemblyException.unmovableBlock(incompatible, level.getBlockState(incompatible));
            }
        }

        if (MiniPhysicsAssemblyContext.isActive() || detachedSelected) {
            AABB query = new AABB(
                    assemblyBounds.minX(),
                    assemblyBounds.minY(),
                    assemblyBounds.minZ(),
                    assemblyBounds.maxX() + 1.0,
                    assemblyBounds.maxY() + 1.0,
                    assemblyBounds.maxZ() + 1.0).inflate(2.0);
            for (ControlledContraptionEntity entity :
                    level.getEntitiesOfClass(ControlledContraptionEntity.class, query)) {
                BlockPos controller = ((ControlledContraptionEntityAccessor) entity).getControllerPos();
                if (blocks.contains(controller)) {
                    // Simulated would disassemble the live Create contraption and append positions
                    // that never passed its BFS. For Frame children this violates exact-source
                    // isolation; for detached mini bodies it could introduce a body whose scale is
                    // not knowable from the gathered block collection. Fail before any mutation.
                    throw AssemblyException.unmovableBlock(controller, level.getBlockState(controller));
                }
            }
        }
        original.call(level, assemblyBounds, blocks, passGluesBack, collectedGlues);
    }

    private static boolean containsDetached(Level level, Collection<BlockPos> blocks) {
        for (BlockPos block : blocks) {
            if (DetachedMiniPhysicsSubLevelService.isDetached(Sable.HELPER.getContaining(level, block))) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos firstNonHalfScalePosition(Level level, Collection<BlockPos> blocks) {
        for (BlockPos block : blocks) {
            SubLevel containing = Sable.HELPER.getContaining(level, block);
            // Root world is macro scale and is therefore incompatible with an Antikythera 0.5 body.
            if (containing == null || !DetachedMiniPhysicsSubLevelService.hasHalfScale(containing)) {
                return block;
            }
        }
        return null;
    }
}
