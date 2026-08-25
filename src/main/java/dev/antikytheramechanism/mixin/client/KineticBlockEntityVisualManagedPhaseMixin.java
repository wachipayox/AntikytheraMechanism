package dev.antikytheramechanism.mixin.client;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import dev.antikytheramechanism.client.ManagedMiniKineticPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Create's checkerboard shaft/cog phase use the visible physical mini lattice and axis. */
@Mixin(value = KineticBlockEntityVisual.class, remap = false)
abstract class KineticBlockEntityVisualManagedPhaseMixin {
    @Inject(method = "rotationOffset", at = @At("HEAD"), cancellable = true)
    private static void antikytheramechanism$usePhysicalManagedMiniPhase(
            BlockState state,
            Direction.Axis logicalAxis,
            Vec3i position,
            CallbackInfoReturnable<Float> callback) {
        if (!(position instanceof BlockPos blockPos)) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ManagedMiniKineticPhase.PhaseContext context = ManagedMiniKineticPhase.resolve(level, blockPos);
        if (context == null) {
            return;
        }

        Direction logicalPositive = Direction.fromAxisAndDirection(
                logicalAxis, Direction.AxisDirection.POSITIVE);
        Direction.Axis physicalAxis = context.orientation().toPhysical(logicalPositive).getAxis();
        BlockPos physical = context.physicalMiniPosition();
        int x = physicalAxis == Direction.Axis.X ? 0 : physical.getX();
        int y = physicalAxis == Direction.Axis.Y ? 0 : physical.getY();
        int z = physicalAxis == Direction.Axis.Z ? 0 : physical.getZ();
        boolean checkerboardOffset = Math.floorMod(x + y + z, 2) == 0;
        callback.setReturnValue(checkerboardOffset ? 22.5F : ICogWheel.isLargeCog(state) ? 11.25F : 0.0F);
    }
}
