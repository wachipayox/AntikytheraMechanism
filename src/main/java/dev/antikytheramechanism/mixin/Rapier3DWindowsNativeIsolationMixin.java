package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Prevents Sable's fixed Rapier extraction path from colliding with a DLL loaded by another JVM.
 *
 * <p>Sable 2.0.3 extracts Rapier to {@code .sable/natives/<native name>} and deletes an existing
 * file before copying the bundled library. Windows forbids deleting a DLL while another process has
 * it loaded, so a stale or concurrently running development client can make the next integrated
 * server crash before any world data is loaded. A PID-scoped filename preserves Sable's extraction
 * and {@link System#load(String)} flow while giving every JVM its own Windows DLL.</p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false)
abstract class Rapier3DWindowsNativeIsolationMixin {
    @ModifyArg(
            method = "loadLibrary",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;"),
            index = 0)
    private static String antikytheramechanism$isolateWindowsNative(String nativeName) {
        if (!nativeName.endsWith("_windows.dll")) {
            return nativeName;
        }

        long processId = ProcessHandle.current().pid();
        int extensionStart = nativeName.lastIndexOf('.');
        String isolatedName = extensionStart < 0
                ? nativeName + "-pid" + processId
                : nativeName.substring(0, extensionStart)
                        + "-pid" + processId
                        + nativeName.substring(extensionStart);

        AntikytheraMechanism.LOGGER.debug(
                "Using process-isolated Sable Rapier native {} instead of {}",
                isolatedName,
                nativeName);
        return isolatedName;
    }
}
