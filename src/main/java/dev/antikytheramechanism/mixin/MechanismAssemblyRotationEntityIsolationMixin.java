package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A wrench yaw is an instantaneous edit of a placed Frame, not physical motion that should carry an
 * entity standing beside it. Sable intentionally sticks entities to moving SubLevels; clear that
 * transient tracking relation around the edited Frame before its managed child is teleported to the
 * new discrete pose. Normal collision reacquires tracking on the following movement tick.
 */
@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyRotationEntityIsolationMixin {
    @WrapMethod(method = "rotateFrame")
    private boolean antikytheramechanism$releaseEntitiesBeforeDiscreteYaw(
            ServerLevel level,
            BlockPos framePos,
            Direction newFacing,
            Operation<Boolean> original) {
        MechanismAssemblyManager manager = (MechanismAssemblyManager) (Object) this;
        MechanismAssembly source = manager.getAssemblyAt(framePos).orElse(null);
        if (source != null
                && newFacing != null
                && !newFacing.getAxis().isVertical()
                && level.hasChunkAt(framePos)
                && level.getBlockState(framePos).hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && level.getBlockState(framePos).getValue(BlockStateProperties.HORIZONTAL_FACING) != newFacing) {
            ServerSubLevel child = MechanismSubLevelService.findExisting(level, source);
            if (child != null && !child.isRemoved()) {
                AABB vicinity = new AABB(framePos).inflate(1.5);
                for (Entity entity : level.getAllEntities()) {
                    if (entity.getBoundingBox().intersects(vicinity)
                            && Sable.HELPER.getTrackingSubLevel(entity) == child
                            && entity instanceof EntityMovementExtension movement) {
                        movement.sable$setTrackingSubLevel(null);
                    }
                }
            }
        }
        return original.call(level, framePos, newFacing);
    }
}
