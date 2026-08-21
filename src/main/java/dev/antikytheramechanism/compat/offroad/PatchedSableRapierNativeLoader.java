package dev.antikytheramechanism.compat.offroad;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Loads the optional Sable 2.0.5 Rapier native patched for the Offroad continuous-force experiment.
 *
 * <p>During the 2.0.5 validation transition an externally supplied, hash-verified native can be placed
 * in the override directory. Bundled natives are accepted only when their hash is known for the exact
 * 2.0.5 build, preventing an older 2.0.3 backend from being loaded accidentally. If no validated
 * native is available, Sable's normal native loader is allowed to continue unchanged.</p>
 */
public final class PatchedSableRapierNativeLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_ROOT = "/natives/antikytheramechanism_sable_rapier/";
    private static final Path EXTRACT_DIR = Paths.get(".antikythera", "natives", "sable-2.0.5-force-prototype");
    private static final Path OVERRIDE_DIR = EXTRACT_DIR.resolve("override");

    // User-built Sable 2.0.5 x86-64 Windows native, verified to export the Antikythera JNI bridge.
    private static final String WINDOWS_X86_64_SHA256 = "2ceedfd65da398ea2d1841d1f84ff550818fa50fc70663df3f8a5c378e5bf166";

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
        final String expectedSha256 = expectedSha256(nativeName);
        if (expectedSha256 == null) {
            failure = "no validated Sable 2.0.5 native hash for " + nativeName;
            return false;
        }

        try {
            Files.createDirectories(EXTRACT_DIR);

            final Path override = OVERRIDE_DIR.resolve(nativeName);
            if (Files.isRegularFile(override)) {
                if (!expectedSha256.equalsIgnoreCase(sha256(override))) {
                    failure = "external override hash mismatch for " + nativeName;
                    LOGGER.warn("Ignoring Antikythera Sable native override '{}' because its SHA-256 does not match the validated build", override);
                    return false;
                }
                System.load(override.toAbsolutePath().toString());
                loaded = true;
                failure = "none";
                LOGGER.info("Loaded external Antikythera patched Sable 2.0.5 Rapier native '{}'", override);
                return true;
            }

            final String resourcePath = RESOURCE_ROOT + nativeName;
            try (InputStream input = PatchedSableRapierNativeLoader.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    failure = "patched resource not bundled for " + nativeName;
                    return false;
                }

                final Path extracted = EXTRACT_DIR.resolve(nativeName);
                final Path temporary = EXTRACT_DIR.resolve(nativeName + ".tmp");
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);

                if (!expectedSha256.equalsIgnoreCase(sha256(temporary))) {
                    Files.deleteIfExists(temporary);
                    failure = "bundled native hash does not match validated Sable 2.0.5 build for " + nativeName;
                    LOGGER.warn("Ignoring bundled Antikythera Sable native '{}' because it is not the validated Sable 2.0.5 build", nativeName);
                    return false;
                }

                try {
                    Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignored) {
                    Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING);
                }

                System.load(extracted.toAbsolutePath().toString());
                loaded = true;
                failure = "none";
                LOGGER.info("Loaded bundled Antikythera patched Sable 2.0.5 Rapier native '{}'", nativeName);
                return true;
            }
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
            LOGGER.warn("Could not load Antikythera patched Sable 2.0.5 Rapier native '{}'; falling back to Sable stock native", nativeName, throwable);
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

    private static String expectedSha256(String nativeName) {
        if ("sable_rapier_x86_64_windows.dll".equals(nativeName)) {
            return WINDOWS_X86_64_SHA256;
        }
        return null;
    }

    private static String sha256(Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
