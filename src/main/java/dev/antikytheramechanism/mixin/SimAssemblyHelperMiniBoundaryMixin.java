package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
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
 * Closes Simulated's post-search Create-contraption expansion when assembly started in a Frame mini world.
 *
 * <p>SimAssemblyContraption itself is constrained by {@link MiniPhysicsAssemblyContext}, but
 * SimAssemblyHelper normally performs one later pass that can disassemble a ControlledContraptionEntity
 * and append all of its blocks directly to the already-validated block collection. Those appended
 * positions never pass movementAllowed. For mini assembly we fail before that destructive expansion,
 * rather than risk crossing the exact source-sublevel boundary or leaving a controller detached from
 * a still-live Create contraption.</p>
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
        if (MiniPhysicsAssemblyContext.isActive()) {
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
                    // Use Create's normal assembly failure shape so Simulated's existing catch/UI path
                    // handles this exactly like any other temporarily unmovable selected block.
                    throw AssemblyException.unmovableBlock(controller, level.getBlockState(controller));
                }
            }
        }
        original.call(level, assemblyBounds, blocks, passGluesBack, collectedGlues);
    }
}
