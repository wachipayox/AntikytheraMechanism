package dev.antikytheramechanism.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionMovementSetting;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.AntikytheraMechanismApi;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Create-linked implementation; loaded reflectively only when Create exists. */
public final class CreateIntegration {
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private CreateIntegration() {
    }

    public static void register(IEventBus modBus) {
        if (LISTENER_REGISTERED.compareAndSet(false, true)) {
            modBus.addListener(CreateIntegration::onCommonSetup);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(CreateIntegration::install);
    }

    private static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Block frameBlock = ModRegistries.MECHANISM_FRAME.get();
        ContraptionMovementSetting.REGISTRY.register(
                frameBlock,
                () -> ContraptionMovementSetting.NO_PICKUP);
        BlockMovementChecks.registerMovementAllowedCheck(
                (state, level, position) -> CreateFrameMovementRules.movementAllowed(
                        frameBlock, state, level, position));
        BlockMovementChecks.registerAttachedCheck(
                (state, level, position, direction) -> CreateFrameMovementRules.attached(
                        frameBlock, state, level, position, direction));
        MovementBehaviour.REGISTRY.register(frameBlock, new MechanismFrameMovementBehaviour(frameBlock));
        CreateMiniKineticLifecycle.register();

        // Deliberately narrow compatibility set. Moving actors and proprietary transport blocks remain
        // denied until they have explicit transactional adapters.
        AntikytheraMechanismApi.allow(AllBlocks.SHAFT.get());
        AntikytheraMechanismApi.allow(AllBlocks.COGWHEEL.get());
        AntikytheraMechanismApi.allow(AllBlocks.LARGE_COGWHEEL.get());
        AntikytheraMechanismApi.allow(AllBlocks.GEARBOX.get());
        AntikytheraMechanismApi.allow(AllBlocks.CLUTCH.get());
        AntikytheraMechanismApi.allow(AllBlocks.GEARSHIFT.get());
        AntikytheraMechanismApi.allow(AllBlocks.ANDESITE_ENCASED_SHAFT.get());
        AntikytheraMechanismApi.allow(AllBlocks.BRASS_ENCASED_SHAFT.get());
        AntikytheraMechanismApi.allow(AllBlocks.ENCASED_CHAIN_DRIVE.get());
        AntikytheraMechanismApi.allow(AllBlocks.SPEEDOMETER.get());
        AntikytheraMechanismApi.allow(AllBlocks.STRESSOMETER.get());
        AntikytheraMechanismApi.allow(AllBlocks.HAND_CRANK.get());
        AntikytheraMechanism.LOGGER.info(
                "Create compatibility installed: movement lifecycle and native mini kinetic topology are enabled");
    }
}
