package dev.antikytheramechanism.server;

import dev.antikytheramechanism.AntikytheraMechanism;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic watchdog for Antikythera/Sable server freezes.
 *
 * <p>It is intentionally independent from the Minecraft server thread. Normal Frame operations use
 * a short, sensitive window while bootstrap and shutdown/save use longer thresholds because regular
 * level-tick heartbeats do not exist in those lifecycle phases.</p>
 */
public final class ServerFreezeWatchdog {
    private static final long FRAME_STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long FRAME_OBSERVATION_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long BOOTSTRAP_STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long BOOTSTRAP_OBSERVATION_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final long SHUTDOWN_STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long SHUTDOWN_OBSERVATION_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long POLL_MILLIS = 250L;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile Thread serverThread;
    private static volatile long lastHeartbeatNanos;
    private static volatile long armedUntilNanos;
    private static volatile long stallThresholdNanos = FRAME_STALL_THRESHOLD_NANOS;
    private static volatile long activeGeneration;
    private static volatile String reason = "not armed";

    private ServerFreezeWatchdog() {
    }

    public static void arm(Thread thread, String diagnosticReason) {
        armInternal(
                thread,
                diagnosticReason,
                FRAME_STALL_THRESHOLD_NANOS,
                FRAME_OBSERVATION_WINDOW_NANOS,
                "30s");
    }

    /** Arms before any dimensions/SubLevels are loaded so 0%-loading freezes can be captured. */
    public static void armBootstrap(Thread thread, String diagnosticReason) {
        armInternal(
                thread,
                diagnosticReason,
                BOOTSTRAP_STALL_THRESHOLD_NANOS,
                BOOTSTRAP_OBSERVATION_WINDOW_NANOS,
                "3min bootstrap window");
    }

    /**
     * Arms before Minecraft enters {@code stopServer()}, which performs the final world/player data
     * save and closes levels/storage. There are no level ticks here, so elapsed time itself is the
     * progress signal. A 30-second stop is already abnormal enough to be useful diagnostically, but
     * the watchdog never kills or interrupts the server: it only emits one dump and keeps observing.
     */
    public static void armShutdown(Thread thread, String diagnosticReason) {
        armInternal(
                thread,
                diagnosticReason,
                SHUTDOWN_STALL_THRESHOLD_NANOS,
                SHUTDOWN_OBSERVATION_WINDOW_NANOS,
                "5min shutdown/save window");
    }

    private static void armInternal(
            Thread thread,
            String diagnosticReason,
            long thresholdNanos,
            long observationNanos,
            String windowDescription) {
        ensureStarted();
        long now = System.nanoTime();
        long generation = GENERATION.incrementAndGet();
        serverThread = thread;
        lastHeartbeatNanos = now;
        stallThresholdNanos = thresholdNanos;
        armedUntilNanos = now + observationNanos;
        reason = diagnosticReason;
        DUMPED.set(false);
        activeGeneration = generation;
        AntikytheraMechanism.LOGGER.warn(
                "[FREEZE-WATCHDOG] Armed {} after {} on thread {} (stall threshold={}ms, generation={})",
                windowDescription,
                diagnosticReason,
                thread.getName(),
                TimeUnit.NANOSECONDS.toMillis(thresholdNanos),
                generation);
    }

    /**
     * Cancels the current observation window. Generation invalidation prevents a watchdog iteration
     * that already sampled the previous arm from producing a stale dump after a clean shutdown.
     */
    public static void disarm(String diagnosticReason) {
        long generation = GENERATION.incrementAndGet();
        Thread previous = serverThread;
        serverThread = null;
        activeGeneration = generation;
        armedUntilNanos = 0L;
        DUMPED.set(false);
        if (previous != null) {
            AntikytheraMechanism.LOGGER.info(
                    "[FREEZE-WATCHDOG] Disarmed after {} (generation={})",
                    diagnosticReason,
                    generation);
        }
    }

    /** Records that the Minecraft server thread is still making forward progress. */
    public static void heartbeat() {
        Thread expected = serverThread;
        if (expected != null && Thread.currentThread() == expected) {
            lastHeartbeatNanos = System.nanoTime();
        }
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread watchdog = new Thread(ServerFreezeWatchdog::runLoop, "Antikythera Server Freeze Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.NORM_PRIORITY + 1);
        watchdog.start();
    }

    private static void runLoop() {
        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            long observedGeneration = activeGeneration;
            Thread target = serverThread;
            if (target == null || DUMPED.get()) {
                continue;
            }

            long now = System.nanoTime();
            long observedHeartbeat = lastHeartbeatNanos;
            long observedThreshold = stallThresholdNanos;
            long observedUntil = armedUntilNanos;
            String observedReason = reason;
            if (now > observedUntil) {
                continue;
            }
            if (now - observedHeartbeat < observedThreshold) {
                continue;
            }
            if (observedGeneration != activeGeneration || target != serverThread) {
                continue;
            }
            if (!DUMPED.compareAndSet(false, true)) {
                continue;
            }
            // A clean ServerStoppedEvent can race the compareAndSet by a few instructions. Check the
            // generation once more before the expensive thread/deadlock snapshot and suppress stale
            // output if the lifecycle has already completed.
            if (observedGeneration != activeGeneration || target != serverThread) {
                DUMPED.set(false);
                continue;
            }

            dumpServerThread(target, now - observedHeartbeat, observedReason);
        }
    }

    private static void dumpServerThread(Thread target, long stalledNanos, String diagnosticReason) {
        StringBuilder dump = new StringBuilder(4096);
        dump.append("\n================ ANTIKYTHERA SERVER FREEZE DUMP ================\n")
                .append("Reason: ").append(diagnosticReason).append('\n')
                .append("No server heartbeat/progress for: ")
                .append(TimeUnit.NANOSECONDS.toMillis(stalledNanos)).append(" ms\n")
                .append("Thread: ").append(target.getName())
                .append(" [id=").append(target.threadId())
                .append(", state=").append(target.getState()).append("]\n");

        StackTraceElement[] stack = target.getStackTrace();
        if (stack.length == 0) {
            dump.append("  <no Java stack frames available; thread may be blocked in native code>\n");
        } else {
            for (StackTraceElement element : stack) {
                dump.append("  at ").append(element).append('\n');
            }
        }

        appendDeadlockInfo(dump);
        dump.append("================ END ANTIKYTHERA FREEZE DUMP ==================\n");

        AntikytheraMechanism.LOGGER.error(dump.toString());
    }

    private static void appendDeadlockInfo(StringBuilder dump) {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long[] deadlocked = bean.findDeadlockedThreads();
            if (deadlocked == null || deadlocked.length == 0) {
                dump.append("JVM deadlock detector: no Java monitor/ownable-synchronizer deadlock found.\n");
                return;
            }

            dump.append("JVM deadlock detector found ").append(deadlocked.length).append(" thread(s):\n");
            ThreadInfo[] infos = bean.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                if (info == null) {
                    continue;
                }
                dump.append("  \"").append(info.getThreadName()).append("\" state=")
                        .append(info.getThreadState()).append('\n');
                for (StackTraceElement element : info.getStackTrace()) {
                    dump.append("    at ").append(element).append('\n');
                }
            }
        } catch (Throwable diagnosticFailure) {
            dump.append("JVM deadlock detector failed: ").append(diagnosticFailure).append('\n');
        }
    }
}
