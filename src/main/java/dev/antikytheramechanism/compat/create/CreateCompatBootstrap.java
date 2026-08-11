package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;

/**
 * Core-side entry point for the optional Create integration.
 *
 * <p>The implementation class name is deliberately a string. No descriptor in
 * this always-loaded class references Create, so a normal installation does not
 * ask the JVM to resolve any Create classes.</p>
 */
public final class CreateCompatBootstrap {
    private static final String CREATE_MOD_ID = "create";
    private static final String IMPLEMENTATION_CLASS =
            "dev.antikytheramechanism.compat.create.CreateIntegration";

    private CreateCompatBootstrap() {
    }

    public static void registerIfLoaded(IEventBus modBus) {
        if (!ModList.get().isLoaded(CREATE_MOD_ID)) {
            AntikytheraMechanism.LOGGER.debug("Create is absent; Create compatibility remains unloaded");
            return;
        }

        try {
            Class<?> implementation = Class.forName(
                    IMPLEMENTATION_CLASS,
                    true,
                    CreateCompatBootstrap.class.getClassLoader());
            implementation.getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalStateException("Create compatibility initialization failed", cause);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Create is loaded but its compatibility layer could not link", exception);
        }
    }
}
