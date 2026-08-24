package dev.antikytheramechanism.compat.create.transmission;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.InvocationTargetException;

/** Keeps dedicated-server class loading away from the client renderer/highlight implementation. */
public final class CreateTransmissionClientBootstrap {
    private static final String CLIENT_CLASS =
            "dev.antikytheramechanism.compat.create.transmission.client.CreateTransmissionClient";

    private CreateTransmissionClientBootstrap() {
    }

    public static void registerIfClient(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            Class<?> client = Class.forName(
                    CLIENT_CLASS,
                    true,
                    CreateTransmissionClientBootstrap.class.getClassLoader());
            client.getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Transmission Box client initialization failed", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Transmission Box client layer could not link", exception);
        }
    }
}
