package dev.antikytheramechanism.client;

import dev.antikytheramechanism.AntikytheraMechanism;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary diagnostic watchdog for client/render-thread freezes while Antikythera SubLevels exist. */
public final class ClientFreezeWatchdog {
    private static final long STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long POLL_MILLIS = 250L;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();

    private static volatile Thread clientThread;
    private static volatile long lastHeartbeatNanos;
    private static volatile boolean armed;
    private static volatile String reason = "not armed";

    private ClientFreezeWatchdog() {
    }

    /**
     * Arms the watchdog persistently. It stays active until process shutdown so a later particle,
     * render, lighting or removal stall is still diagnosable; each explicit arm resets any previous
     * one-shot dump and reason.
     */
    public static void arm(Thread thread, String diagnosticReason) {
        ensureStarted();
        long now = System.nanoTime();
        clientThread = thread;
        lastHeartbeatNanos = now;
        armed = true;
        reason = diagnosticReason;
        DUMPED.set(false);
        AntikytheraMechanism.LOGGER.warn(
                "[CLIENT-FREEZE-WATCHDOG] Armed persistently after {} on thread {}",
                diagnosticReason,
                thread.getName());
    }

    /** Records that Minecraft's client thread completed another tick. */
    public static void heartbeat() {
        if (!armed) {
            return;
        }

        Thread current = Thread.currentThread();
        Thread expected = clientThread;
        // Minecraft.tick is the authoritative client thread. If packet handling happened on a
        // different executor, adopt the actual ticking thread on the first heartbeat.
        if (expected == null || expected != current) {
            clientThread = current;
        }
        lastHeartbeatNanos = System.nanoTime();
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread watchdog = new Thread(ClientFreezeWatchdog::runLoop, "Antikythera Client Freeze Watchdog");
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

            Thread target = clientThread;
            if (!armed || target == null || DUMPED.get()) {
                continue;
            }

            long now = System.nanoTime();
            if (now - lastHeartbeatNanos < STALL_THRESHOLD_NANOS) {
                continue;
            }
            if (!DUMPED.compareAndSet(false, true)) {
                continue;
            }

            dumpClientThread(target, now - lastHeartbeatNanos);
        }
    }

    private static void dumpClientThread(Thread target, long stalledNanos) {
        StringBuilder dump = new StringBuilder(4096);
        dump.append("\n================ ANTIKYTHERA CLIENT FREEZE DUMP ================\n")
                .append("Reason: ").append(reason).append('\n')
                .append("No client heartbeat for: ")
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
        dump.append("================ END ANTIKYTHERA CLIENT FREEZE DUMP ==================\n");
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
