package dev.antikytheramechanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Allows ordinary Antikythera macro blocks to participate in the read-only shell projected around a
 * managed Frame. Mechanism Frames themselves are already rejected explicitly immediately before the
 * namespace guard in MiniWorldEnvironment, so rejecting the whole mod namespace became too broad as
 * soon as the Transmission Box was added: a block placed from the box face saw AIR as its synthetic
 * support on the server and the otherwise-valid mini placement silently failed after client swing.
 */
@Mixin(MiniWorldEnvironment.class)
abstract class MiniWorldEnvironmentOwnMacroSupportMixin {
    @WrapOperation(
            method = "virtualBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private static boolean antikytheramechanism$allowOwnNonFrameMacroSupport(
            String expectedNamespace,
            Object actualNamespace,
            Operation<Boolean> original) {
        if (AntikytheraMechanism.MOD_ID.equals(expectedNamespace)) {
            return false;
        }
        return original.call(expectedNamespace, actualNamespace);
    }
}
