package dev.antikytheramechanism.server;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.assembly.PistonAssemblyMovement;
import dev.antikytheramechanism.sublevel.FrameMaskOverflowDropService;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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

    public static void onServerStopping(ServerStoppingEvent event) {
        // NeoForge fires this after the main run loop exits but before MinecraftServer#stopServer,
        // where player/world data, dimensions, chunk storage and Sable SubLevels are saved/closed.
        // No LevelTick heartbeat exists from this point onward, so the shutdown watchdog measures the
        // elapsed save/close phase and dumps the server thread if it remains stuck for 30 seconds.
        ServerFreezeWatchdog.armShutdown(Thread.currentThread(), "server/world shutdown and save");
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        // Invalidate the shutdown generation before the daemon can report a stale freeze after a
        // completely normal stop. The watchdog is diagnostic only and never delays shutdown itself.
        ServerFreezeWatchdog.disarm("server/world shutdown completed");
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Finish any real out-of-mask placement only after its originating call stack has had a
            // chance to hydrate BlockEntity NBT/storage. Recovery runs before lazy retirement so the
            // transient plot cell can never keep an otherwise-empty managed SubLevel alive.
            FrameMaskOverflowDropService.tick(serverLevel);

            // Retire physical Sable worlds only after normal block/update call stacks have finished.
            // This also migrates legacy assemblies that were saved with an idle empty SubLevel.
            LazySubLevelLifecycle.tick(serverLevel);

            // poseTarget remains local to whichever physical host stores the Frames. Moving/rotating
            // foreign hosts are composed into a world target only by AssemblyPoseDriver, so ordinary
            // manager maintenance keeps exactly the same local FrameGraph invariants as root Frames.
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
