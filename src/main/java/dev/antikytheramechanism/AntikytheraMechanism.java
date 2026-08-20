package dev.antikytheramechanism;

import com.mojang.logging.LogUtils;
import dev.antikytheramechanism.compat.create.CreateCompatBootstrap;
import dev.antikytheramechanism.compat.offroad.OffroadWheelDiagnostics;
import dev.antikytheramechanism.compat.offroad.OffroadWheelHeldImpulseDiagnostics;
import dev.antikytheramechanism.config.AntikytheraCommonConfig;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.server.AntikytheraServerEvents;
import dev.antikytheramechanism.sublevel.AntikytheraSubLevelObserver;
import dev.antikytheramechanism.sublevel.AssemblyPoseDriver;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.ManagedSubLevelCollisionPolicy;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniPhysicsBuiltins;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AntikytheraMechanism.MOD_ID)
public final class AntikytheraMechanism {
    public static final String MOD_ID = "antikytheramechanism";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public AntikytheraMechanism(IEventBus modBus, ModContainer modContainer) {
        MechanismSubLevelService.bootstrap();
        DetachedMiniPhysicsSubLevelService.bootstrap();
        MiniPhysicsBuiltins.bootstrap();
        ModRegistries.register(modBus);
        CreateCompatBootstrap.registerIfLoaded(modBus);
        OffroadWheelDiagnostics.registerPhysicsHook();
        modContainer.registerConfig(ModConfig.Type.COMMON, AntikytheraCommonConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(AntikytheraServerEvents::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(AntikytheraServerEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(AntikytheraServerEvents::onServerStopped);
        NeoForge.EVENT_BUS.addListener(AntikytheraServerEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(AntikytheraServerEvents::onPistonPre);
        NeoForge.EVENT_BUS.addListener(AntikytheraSubLevelObserver::onContainerReady);
        NeoForge.EVENT_BUS.addListener(ManagedSubLevelCollisionPolicy::onPrePhysicsTick);
        NeoForge.EVENT_BUS.addListener(AssemblyPoseDriver::onPostPhysicsTick);
        NeoForge.EVENT_BUS.addListener(OffroadWheelDiagnostics::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(OffroadWheelHeldImpulseDiagnostics::onRegisterCommands);
        LOGGER.info("Antikythera Mechanism initialized");
    }
}
