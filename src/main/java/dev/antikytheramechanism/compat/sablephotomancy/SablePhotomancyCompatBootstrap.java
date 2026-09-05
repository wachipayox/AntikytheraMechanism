package dev.antikytheramechanism.compat.sablephotomancy;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;

/** Keeps the optional Sable Photomancy API out of always-loaded class descriptors. */
public final class SablePhotomancyCompatBootstrap {
    private static final String MOD_ID = "sable_schematic_api";
    private static final String IMPLEMENTATION_CLASS =
            "dev.antikytheramechanism.compat.sablephotomancy.SablePhotomancyIntegration";

    private SablePhotomancyCompatBootstrap() {
    }

    public static void registerIfLoaded() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            AntikytheraMechanism.LOGGER.debug("Sable Photomancy is absent; schematic API compatibility remains unloaded");
            return;
        }

        try {
            Class<?> implementation = Class.forName(
                    IMPLEMENTATION_CLASS,
                    true,
                    SablePhotomancyCompatBootstrap.class.getClassLoader());
            implementation.getMethod("register").invoke(null);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Sable Photomancy compatibility initialization failed", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "Sable Photomancy is loaded but its Antikythera compatibility layer could not link",
                    exception);
        }
    }
}
