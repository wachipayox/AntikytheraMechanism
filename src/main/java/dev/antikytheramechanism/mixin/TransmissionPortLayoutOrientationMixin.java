package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlock;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxKind;
import dev.antikytheramechanism.compat.create.transmission.TransmissionFaceOrientation;
import dev.antikytheramechanism.compat.create.transmission.TransmissionLinkCoordinator;
import dev.antikytheramechanism.compat.create.transmission.TransmissionPortLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = TransmissionLinkCoordinator.class, remap = false)
abstract class TransmissionPortLayoutOrientationMixin {
    @Unique private static final ThreadLocal<FrameOrientation> antikytheramechanism$orientation = new ThreadLocal<>();

    @Inject(method = "reconcile", at = @At("HEAD"), remap = false)
    private static void antikytheramechanism$capture(
            ServerLevel level, BlockPos boxPosition, CallbackInfo ci) {
        BlockState state = level.getBlockState(boxPosition);
        if (!(state.getBlock() instanceof TransmissionBoxBlock)) return;
        BlockPos frame = boxPosition.relative(state.getValue(TransmissionBoxBlock.FACING));
        MechanismAssemblyManager.get(level).getAssemblyAt(frame)
                .ifPresent(assembly -> antikytheramechanism$orientation.set(assembly.orientation()));
    }

    @Redirect(
            method = "reconcile",
            at = @At(value = "INVOKE", target = "Ldev/antikytheramechanism/compat/create/transmission/TransmissionPortLayout;create(Ldev/antikytheramechanism/compat/create/transmission/TransmissionBoxKind;Ldev/antikytheramechanism/compat/create/transmission/TransmissionFaceOrientation;ZILnet/minecraft/core/BlockPos;)Ljava/util/List;"),
            remap = false)
    private static List<TransmissionPortLayout.PortPlacement> antikytheramechanism$logicalLayout(
            TransmissionBoxKind kind,
            TransmissionFaceOrientation orientation,
            boolean diagonal,
            int coverMask,
            BlockPos frameMiniBase) {
        FrameOrientation frameOrientation = antikytheramechanism$orientation.get();
        TransmissionFaceOrientation logical = frameOrientation == null
                ? orientation
                : orientation.toLogical(frameOrientation);
        return TransmissionPortLayout.create(kind, logical, diagonal, coverMask, frameMiniBase);
    }

    @Inject(method = "reconcile", at = @At("RETURN"), remap = false)
    private static void antikytheramechanism$clear(
            ServerLevel level, BlockPos boxPosition, CallbackInfo ci) {
        antikytheramechanism$orientation.remove();
    }
}
