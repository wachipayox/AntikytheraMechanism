package dev.antikytheramechanism.compat.offroad;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the optional Sable 2.0.5 Rapier native patched for the Offroad continuous-force experiment.
 *
 * <p>The patched library is byte-for-byte the normal Sable Rapier backend apart from one additional
 * JNI entry point used by {@link OffroadNativeForceBridge}. If a patched binary for the current
 * platform is not bundled, or loading fails, this class returns false and Sable's normal native loader
 * is allowed to continue unchanged.</p>
 */
public final class PatchedSableRapierNativeLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_ROOT = "/natives/antikytheramechanism_sable_rapier/";
    private static final Path EXTRACT_DIR = Paths.get(".antikythera", "natives", "sable-2.0.5-force-prototype");

    private static volatile boolean attempted;
    private static volatile boolean loaded;
    private static volatile String loadedName = "none";
    private static volatile String failure = "none";

    private PatchedSableRapierNativeLoader() {
    }

    public static synchronized boolean tryLoadPatchedNative() {
        if (attempted) {
            return loaded;
        }
        attempted = true;

        final String nativeName = nativeName();
        loadedName = nativeName;
        final String resourcePath = RESOURCE_ROOT + nativeName;

        try (InputStream input = PatchedSableRapierNativeLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                failure = "patched resource not bundled for " + nativeName;
                return false;
            }

            Files.createDirectories(EXTRACT_DIR);
            final Path extracted = EXTRACT_DIR.resolve(nativeName);
            final Path temporary = EXTRACT_DIR.resolve(nativeName + ".tmp");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(extracted.toAbsolutePath().toString());
            loaded = true;
            failure = "none";
            LOGGER.info("Loaded Antikythera patched Sable Rapier native '{}' for continuous-force diagnosis", nativeName);
            return true;
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
            LOGGER.warn("Could not load Antikythera patched Sable Rapier native '{}'; falling back to Sable stock native", nativeName, throwable);
            loaded = false;
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String loadedName() {
        return loadedName;
    }

    public static String failure() {
        return failure;
    }

    private static String nativeName() {
        final String archProperty = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String arch = archProperty.equals("arm") || archProperty.startsWith("aarch64") || archProperty.startsWith("arm64")
                ? "aarch64"
                : "x86_64";

        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "sable_rapier_" + arch + "_windows.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "sable_rapier_" + arch + "_macos.dylib";
        }
        return "sable_rapier_" + arch + "_linux.so";
    }
}
