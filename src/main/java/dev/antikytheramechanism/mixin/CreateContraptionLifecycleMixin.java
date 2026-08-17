package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.compat.create.CreateContraptionAnchorAccess;
import dev.antikytheramechanism.compat.create.CreateContraptionLifecycle;
import dev.antikytheramechanism.compat.create.CreateContraptionPlacementCommit;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Transaction boundaries around Create's public contraption lifecycle. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
abstract class CreateContraptionLifecycleMixin implements CreateContraptionAnchorAccess {
    @Invoker(value = "isAnchoringBlockAt", remap = false)
    @Override
    public abstract boolean antikytheramechanism$isAnchoringBlockAt(BlockPos position);

    @Inject(method = "searchMovedStructure", at = @At("RETURN"), cancellable = true, remap = false)
    private void antikytheramechanism$rejectUnsafeCapture(
            Level level,
            BlockPos position,
            Direction forcedDirection,
            CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()
                && !CreateContraptionLifecycle.preflight((Contraption) (Object) this, level)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "removeBlocksFromWorld", at = @At("HEAD"), remap = false)
    private void antikytheramechanism$journalBeforeExtraction(
            Level level,
            BlockPos offset,
            CallbackInfo callback) {
        if (!CreateContraptionLifecycle.beginRemoval((Contraption) (Object) this, level, offset)) {
            throw new IllegalStateException(
                    "Create Mechanism Frame extraction no longer matches its validated complete assembly");
        }
    }

    @Inject(method = "addBlocksToWorld", at = @At("HEAD"), remap = false)
    private void antikytheramechanism$journalBeforePlacement(
            Level level,
            StructureTransform transform,
            CallbackInfo callback) {
        if (!CreateContraptionLifecycle.beginPlacement((Contraption) (Object) this, level, transform)) {
            throw new IllegalStateException(
                    "Create Mechanism Frame placement would violate its persisted logical frame mapping");
        }
    }

    @Inject(method = "addBlocksToWorld", at = @At("RETURN"), remap = false)
    private void antikytheramechanism$commitAfterPlacement(
            Level level,
            StructureTransform transform,
            CallbackInfo callback) {
        CreateContraptionPlacementCommit.finishPlacement((Contraption) (Object) this, level);
    }

    /**
     * The target support projection is synchronous-only. Unwind it even if Create or a neighbouring
     * mod throws before addBlocksToWorld reaches RETURN; the durable journal remains the sole recovery
     * authority in that case.
     */
    @WrapMethod(method = "addBlocksToWorld", remap = false)
    private void antikytheramechanism$scopePreparedTargetSupport(
            Level level,
            StructureTransform transform,
            Operation<Void> original) {
        int depth = CreateAssemblyPlacementContext.depth();
        try {
            original.call(level, transform);
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }
    }
}
