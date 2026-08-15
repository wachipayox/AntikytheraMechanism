package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.mixin.accessor.ControlledContraptionEntityAccessor;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;

/**
 * Closes Simulated's post-search expansion paths before they can mutate an Antikythera mini body.
 *
 * <p>There are two separate invariants here:</p>
 * <ul>
 *     <li>A Physics Assembler started inside a Frame may only consume its exact managed child.</li>
 *     <li>If an ordinary Simulated search has gathered any detached Antikythera physics body, every
 *     gathered block must belong to a Sable SubLevel at uniform scale 0.5. Root/macro blocks and
 *     1.0 bodies are incompatible. A normal Sable 0.5 body remains intentionally compatible.</li>
 * </ul>
 *
 * <p>This hook runs after Simulated's read-only BFS, but before its Create-contraption expansion,
 * which can disassemble entities and append blocks. A cross-scale slime/glue selection therefore
 * fails like an ordinary unmovable structure before either source body is changed.</p>
 */
@Mixin(value = SimAssemblyHelper.class, remap = false)
abstract class SimAssemblyHelperMiniBoundaryMixin {
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
