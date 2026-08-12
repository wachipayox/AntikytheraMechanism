package dev.antikytheramechanism.server;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PistonAssemblyMovement;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

public final class AntikytheraServerEvents {
    private AntikytheraServerEvents() {
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
