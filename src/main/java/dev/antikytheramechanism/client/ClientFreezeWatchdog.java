package dev.antikytheramechanism.client;

import dev.antikytheramechanism.AntikytheraMechanism;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary diagnostic watchdog for client/render-thread freezes around frame removal. */
public final class ClientFreezeWatchdog {
    private static final long STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long OBSERVATION_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long POLL_MILLIS = 250L;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();

    private static volatile Thread clientThread;
    private static volatile long lastHeartbeatNanos;
    private static volatile long armedUntilNanos;
    private static volatile String reason = "not armed";

    private ClientFreezeWatchdog() {
    }

    public static void arm(Thread thread, String diagnosticReason) {
        ensureStarted();
        long now = System.nanoTime();
        clientThread = thread;
        lastHeartbeatNanos = now;
        armedUntilNanos = now + OBSERVATION_WINDOW_NANOS;
        reason = diagnosticReason;
        DUMPED.set(false);
        AntikytheraMechanism.LOGGER.warn(
                "[CLIENT-FREEZE-WATCHDOG] Armed for 30s after {} on thread {}",
                diagnosticReason,
                thread.getName());
    }

    /** Records that Minecraft's client thread completed another tick. */
    public static void heartbeat() {
        Thread expected = clientThread;
        if (expected != null && Thread.currentThread() == expected) {
            lastHeartbeatNanos = System.nanoTime();
        }
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
            if (target == null || DUMPED.get()) {
                continue;
            }

            long now = System.nanoTime();
            if (now > armedUntilNanos || now - lastHeartbeatNanos < STALL_THRESHOLD_NANOS) {
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
