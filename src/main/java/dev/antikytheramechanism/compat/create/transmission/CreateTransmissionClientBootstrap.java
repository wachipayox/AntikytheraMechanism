package dev.antikytheramechanism.compat.create.transmission;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.InvocationTargetException;

/** Keeps dedicated-server class loading away from the client renderer/highlight implementation. */
public final class CreateTransmissionClientBootstrap {
    private static final String CLIENT_CLASS =
            "dev.antikytheramechanism.compat.create.transmission.client.CreateTransmissionClient";
    private static final String MINI_PLACEMENT_CLIENT_CLASS =
            "dev.antikytheramechanism.compat.create.transmission.client.TransmissionBoxMiniPlacementClient";

    private CreateTransmissionClientBootstrap() {
    }

    public static void registerIfClient(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            ClassLoader loader = CreateTransmissionClientBootstrap.class.getClassLoader();
            Class<?> client = Class.forName(CLIENT_CLASS, true, loader);
            client.getMethod("register", IEventBus.class).invoke(null, modBus);

            Class<?> placementClient = Class.forName(MINI_PLACEMENT_CLIENT_CLASS, true, loader);
            placementClient.getMethod("register").invoke(null);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Transmission Box client initialization failed", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Transmission Box client layer could not link", exception);
        }
    }
}
