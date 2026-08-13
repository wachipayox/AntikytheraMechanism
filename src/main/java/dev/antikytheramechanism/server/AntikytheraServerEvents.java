package dev.antikytheramechanism.server;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PistonAssemblyMovement;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
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
            // Retire physical Sable worlds only after normal block/update call stacks have finished.
            // This also migrates legacy assemblies that were saved with an idle empty SubLevel.
            LazySubLevelLifecycle.tick(serverLevel);

            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            // Empty assemblies have no managed child body and therefore never visit AssemblyPoseDriver.
            // Keep their transient pose coherent with a moving foreign Sable host before merge/split
            // maintenance compares rigid transforms or a first mini block allocates the child world.
            MechanismAssemblyHost.synchronizeAll(serverLevel, manager);

            // Heartbeat both sides of Antikythera's maintenance so the temporary watchdog can
            // distinguish a freeze inside manager.tick from one elsewhere in the server tick.
            ServerFreezeWatchdog.heartbeat();
            manager.tick(serverLevel);
            ServerFreezeWatchdog.heartbeat();
        }
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        dev.antikytheramechanism.sublevel.FrameMaskWriteGuard.onPistonPre(event);
        PistonAssemblyMovement.onPistonPre(event);
    }
}
