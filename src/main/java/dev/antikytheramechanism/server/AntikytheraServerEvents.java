package dev.antikytheramechanism.server;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PistonAssemblyMovement;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class AntikytheraServerEvents {
    private AntikytheraServerEvents() {
    }

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // This fires before dimensions/SubLevels begin loading, when LevelTick heartbeats do not yet
        // exist. Use the relaxed bootstrap threshold so a world stuck at 0% still yields a server
        // thread stack without flagging ordinary short startup stalls.
        ServerFreezeWatchdog.armBootstrap(Thread.currentThread(), "server/world bootstrap");
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Heartbeat both sides of Antikythera's maintenance so the temporary watchdog can
            // distinguish a freeze inside manager.tick from one elsewhere in the server tick.
            ServerFreezeWatchdog.heartbeat();
            MechanismAssemblyManager.get(serverLevel).tick(serverLevel);
            ServerFreezeWatchdog.heartbeat();
        }
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        dev.antikytheramechanism.sublevel.FrameMaskWriteGuard.onPistonPre(event);
        PistonAssemblyMovement.onPistonPre(event);
    }
}
