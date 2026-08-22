package dev.antikytheramechanism.client;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.FramePlacementFeedbackHooks;
import dev.antikytheramechanism.registry.ModRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = AntikytheraMechanism.MOD_ID, value = Dist.CLIENT)
public final class ClientRenderRegistration {
    private ClientRenderRegistration() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        FramePlacementFeedbackHooks.registerRejectedPlacementFeedback(
                HiddenFramePlacementRejectionPulse::trigger);
        event.registerBlockEntityRenderer(
                ModRegistries.MECHANISM_FRAME_BLOCK_ENTITY.get(),
                MechanismFrameOrientationRenderer::new);
    }
}
