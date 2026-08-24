package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.CreateMiniKineticTopology;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Treats a wrench yaw as a real topology mutation for Create networks crossing Frame boundaries.
 * Internal mini coordinates do not move during rotation, so Create cannot otherwise notice that the
 * physical neighbours represented by the virtual bridge changed underneath an existing network.
 */
@Mixin(MechanismAssemblyManager.class)
abstract class MechanismAssemblyRotationKineticsMixin {
    @WrapMethod(method = "rotateFrame")
    private boolean antikytheramechanism$rebuildKineticsAroundFrameYaw(
            ServerLevel level,
            BlockPos framePos,
            Direction newFacing,
            Operation<Boolean> original) {
        MechanismAssemblyManager manager = (MechanismAssemblyManager) (Object) this;
        MechanismAssembly source = manager.getAssemblyAt(framePos).orElse(null);
        if (source == null
                || newFacing == null
                || newFacing.getAxis().isVertical()
                || !level.hasChunkAt(framePos)
                || !level.getBlockState(framePos).hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || level.getBlockState(framePos).getValue(BlockStateProperties.HORIZONTAL_FACING) == newFacing) {
            return original.call(level, framePos, newFacing);
        }

        Set<BlockPos> watch = boundaryWatch(source.frames());
        Set<MechanismAssembly> before = assembliesAt(manager, watch);
        Set<TransmissionBoxBlockEntity> boxes = transmissionBoxesAt(level, watch);

        // A box may itself hold the source pointer that crosses the Frame boundary, so detach both
        // sides of the virtual edge before changing orientation.
        boxes.forEach(TransmissionBoxBlockEntity::beginTopologyMutation);
        CreateMiniKineticTopology.quiesceAssemblies(level, before);

        boolean result;
        try {
            result = original.call(level, framePos, newFacing);
        } finally {
            // Rotation may split, merge or replace the assembly id. Resolve owners again from the
            // same physical boundary positions instead of trusting stale pre-rotation ids.
            Set<MechanismAssembly> after = assembliesAt(manager, watch);
            manager.getAssemblyAt(framePos).ifPresent(after::add);
            CreateMiniKineticTopology.rebuildAssemblies(level, after);

            Set<TransmissionBoxBlockEntity> currentBoxes = transmissionBoxesAt(level, watch);
            for (TransmissionBoxBlockEntity box : boxes) {
                if (!box.isRemoved()) {
                    currentBoxes.add(box);
                }
            }
            currentBoxes.forEach(TransmissionBoxBlockEntity::finishTopologyMutation);
        }
        return result;
    }

    private static Set<BlockPos> boundaryWatch(Collection<BlockPos> frames) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (BlockPos frame : frames) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        result.add(frame.offset(dx, dy, dz).immutable());
                    }
                }
            }
        }
        return result;
    }

    private static Set<MechanismAssembly> assembliesAt(
            MechanismAssemblyManager manager,
            Collection<BlockPos> positions) {
        Set<MechanismAssembly> result = new LinkedHashSet<>();
        for (BlockPos position : positions) {
            manager.getAssemblyAt(position).ifPresent(result::add);
        }
        return result;
    }

    private static Set<TransmissionBoxBlockEntity> transmissionBoxesAt(
            ServerLevel level,
            Collection<BlockPos> positions) {
        Set<TransmissionBoxBlockEntity> result = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (BlockPos position : positions) {
            if (level.hasChunkAt(position)
                    && level.getBlockEntity(position) instanceof TransmissionBoxBlockEntity box) {
                result.add(box);
            }
        }
        return result;
    }
}
