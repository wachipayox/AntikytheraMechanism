package dev.antikytheramechanism.client;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temporary, low-noise profiler for the macro-block-near-Frame FPS regression.
 *
 * <p>The hot-path side only performs nanoTime calls and atomic counter updates while a short sample
 * is armed. A daemon sampler inspects the client thread every few milliseconds and emits one aggregate
 * report afterwards. Nothing is logged per particle or per frame.</p>
 */
public final class ClientParticlePerfProbe {
    private static final long INITIAL_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2500L);
    private static final long POST_DESTROY_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2000L);
    private static final long MAX_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long REARM_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long SAMPLE_SLEEP_MILLIS = 4L;
    private static final int TOP_SAMPLE_ROWS = 12;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static volatile Session current;
    private static volatile long cooldownUntilNanos;

    private ClientParticlePerfProbe() {
    }

    /** Arms a short sample after a parent-world crack/destroy has positively found a Mechanism Frame. */
    public static void arm(String trigger, BlockPos sourcePos) {
        long now = System.nanoTime();
        Session session = current;
        if (session != null) {
            session.noteTrigger(trigger, now);
            return;
        }
        if (now < cooldownUntilNanos) {
            return;
        }

        synchronized (ClientParticlePerfProbe.class) {
            session = current;
            if (session != null) {
                session.noteTrigger(trigger, now);
                return;
            }
            if (now < cooldownUntilNanos) {
                return;
            }

            Session created = new Session(
                    NEXT_ID.incrementAndGet(),
                    Thread.currentThread(),
                    sourcePos.immutable(),
                    now,
                    now + INITIAL_WINDOW_NANOS);
            created.noteTrigger(trigger, now);
            current = created;

            AntikytheraMechanism.LOGGER.warn(
                    "[CLIENT-PERF-PROBE] Armed sample #{} after {} at {} for ~{} ms",
                    created.id,
                    trigger,
                    created.sourcePos,
                    TimeUnit.NANOSECONDS.toMillis(INITIAL_WINDOW_NANOS));

            Thread sampler = new Thread(
                    () -> sampleClientThread(created),
                    "Antikythera Client Perf Sampler #" + created.id);
            sampler.setDaemon(true);
            sampler.setPriority(Thread.NORM_PRIORITY);
            sampler.start();
        }
    }

    public static boolean isActive() {
        return current != null;
    }

    /** Returns zero when the probe is idle, allowing callers to avoid a second nanoTime call. */
    public static long startTiming() {
        return current == null ? 0L : System.nanoTime();
    }

    public static void recordContainingLookup(long elapsedNanos) {
        Session session = current;
        if (session != null) {
            session.containingLookupCalls.incrementAndGet();
            session.containingLookupNanos.addAndGet(elapsedNanos);
        }
    }

    public static void recordFrameScan(long elapsedNanos) {
        Session session = current;
        if (session != null) {
            session.frameScanCalls.incrementAndGet();
            session.frameScanNanos.addAndGet(elapsedNanos);
        }
    }

    public static void recordCrack(long startedNanos) {
        recordTimed(startedNanos, Counter.CRACK);
    }

    public static void recordDestroy(long startedNanos) {
        recordTimed(startedNanos, Counter.DESTROY);
    }

    public static void recordExtension(long startedNanos) {
        recordTimed(startedNanos, Counter.EXTENSION);
    }

    public static void recordShapeLookup(long startedNanos) {
        recordTimed(startedNanos, Counter.SHAPE);
    }

    public static void recordParticleConstruct(long startedNanos) {
        recordTimed(startedNanos, Counter.CONSTRUCT);
    }

    public static void recordParticleAdd(long startedNanos) {
        recordTimed(startedNanos, Counter.ADD);
    }

    public static void recordParentLight(long startedNanos) {
        recordTimed(startedNanos, Counter.LIGHT);
    }

    public static void recordDetachedTick(long startedNanos) {
        recordTimed(startedNanos, Counter.DETACHED_TICK);
    }

    public static void recordParentCollision(long startedNanos) {
        recordTimed(startedNanos, Counter.COLLISION);
    }

    public static void recordRender(long startedNanos) {
        Session session = current;
        if (session == null || startedNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startedNanos;
        session.renderCalls.incrementAndGet();
        session.renderNanos.addAndGet(elapsed);
        updateMax(session.maxRenderNanos, elapsed);
    }

    public static void recordClientTick(long startedNanos) {
        Session session = current;
        if (session == null || startedNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startedNanos;
        session.clientTickCalls.incrementAndGet();
        session.clientTickNanos.addAndGet(elapsed);
        updateMax(session.maxClientTickNanos, elapsed);
    }

    /** Counts whether Sable is still asking detached TerrainParticles to consider transformed levels. */
    public static void recordSableCareQuery(boolean caresAboutSubLevels) {
        Session session = current;
        if (session != null) {
            session.sableCareQueries.incrementAndGet();
            if (caresAboutSubLevels) {
                session.sableCareTrue.incrementAndGet();
            }
        }
    }

    /** Counts TerrainParticle light calls that were not classified onto the parent-world fast path. */
    public static void recordUnclassifiedTerrainLight() {
        Session session = current;
        if (session != null) {
            session.unclassifiedTerrainLightCalls.incrementAndGet();
        }
    }

    private static void recordTimed(long startedNanos, Counter counter) {
        Session session = current;
        if (session == null || startedNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startedNanos;
        counter.calls(session).incrementAndGet();
        counter.nanos(session).addAndGet(elapsed);
    }

    private static void updateMax(AtomicLong target, long value) {
        long observed = target.get();
        while (value > observed && !target.compareAndSet(observed, value)) {
            observed = target.get();
        }
    }

    private static void sampleClientThread(Session session) {
        Map<String, Integer> leafSamples = new HashMap<>();
        Map<String, Integer> interestingSamples = new HashMap<>();
        long totalSamples = 0L;
        long runnableSamples = 0L;
        long waitingSamples = 0L;
        long timedWaitingSamples = 0L;
        long blockedSamples = 0L;
        long noStackSamples = 0L;
        long sableSamples = 0L;
        long sableScaleSamples = 0L;
        long antikytheraSamples = 0L;
        long particleSamples = 0L;
        long rendererSamples = 0L;

        while (System.nanoTime() < session.deadlineNanos) {
            try {
                Thread.sleep(SAMPLE_SLEEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }

            Thread target = session.clientThread;
            Thread.State state = target.getState();
            totalSamples++;
            if (state == Thread.State.RUNNABLE) {
                runnableSamples++;
                StackTraceElement[] stack = target.getStackTrace();
                if (stack.length == 0) {
                    noStackSamples++;
                    continue;
                }

                increment(leafSamples, compact(stack[0]));
                String interesting = firstInteresting(stack);
                if (interesting != null) {
                    increment(interestingSamples, interesting);
                }

                boolean sawSable = false;
                boolean sawSableScale = false;
                boolean sawAntikythera = false;
                boolean sawParticle = false;
                boolean sawRenderer = false;
                for (StackTraceElement frame : stack) {
                    String className = frame.getClassName();
                    sawSable |= className.startsWith("dev.ryanhcode.sable.");
                    sawSableScale |= className.startsWith("dev.sablescale.");
                    sawAntikythera |= className.startsWith("dev.antikytheramechanism.");
                    sawParticle |= className.startsWith("net.minecraft.client.particle.");
                    sawRenderer |= className.startsWith("net.minecraft.client.renderer.")
                            || className.startsWith("com.mojang.blaze3d.");
                }
                if (sawSable) sableSamples++;
                if (sawSableScale) sableScaleSamples++;
                if (sawAntikythera) antikytheraSamples++;
                if (sawParticle) particleSamples++;
                if (sawRenderer) rendererSamples++;
            } else if (state == Thread.State.WAITING) {
                waitingSamples++;
            } else if (state == Thread.State.TIMED_WAITING) {
                timedWaitingSamples++;
            } else if (state == Thread.State.BLOCKED) {
                blockedSamples++;
            }
        }

        synchronized (ClientParticlePerfProbe.class) {
            if (current != session) {
                return;
            }
            current = null;
            cooldownUntilNanos = System.nanoTime() + REARM_COOLDOWN_NANOS;
        }

        logReport(
                session,
                totalSamples,
                runnableSamples,
                waitingSamples,
                timedWaitingSamples,
                blockedSamples,
                noStackSamples,
                sableSamples,
                sableScaleSamples,
                antikytheraSamples,
                particleSamples,
                rendererSamples,
                leafSamples,
                interestingSamples);
    }

    private static void increment(Map<String, Integer> samples, String key) {
        samples.merge(key, 1, Integer::sum);
    }

    private static String firstInteresting(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("dev.ryanhcode.sable.")
                    || className.startsWith("dev.sablescale.")
                    || className.startsWith("dev.antikytheramechanism.")
                    || className.startsWith("net.minecraft.client.particle.")
                    || className.startsWith("net.minecraft.client.renderer.")
                    || className.startsWith("com.mojang.blaze3d.")) {
                return compact(frame);
            }
        }
        return null;
    }

    private static String compact(StackTraceElement element) {
        return element.getClassName() + "." + element.getMethodName();
    }

    private static void logReport(
            Session session,
            long totalSamples,
            long runnableSamples,
            long waitingSamples,
            long timedWaitingSamples,
            long blockedSamples,
            long noStackSamples,
            long sableSamples,
            long sableScaleSamples,
            long antikytheraSamples,
            long particleSamples,
            long rendererSamples,
            Map<String, Integer> leafSamples,
            Map<String, Integer> interestingSamples) {
        long wallNanos = System.nanoTime() - session.startedNanos;
        StringBuilder report = new StringBuilder(8192);
        report.append("\n================ ANTIKYTHERA CLIENT PERF SAMPLE #")
                .append(session.id)
                .append(" ================\n")
                .append("Source: ").append(session.sourcePos).append('\n')
                .append("Window: ").append(formatNanos(wallNanos)).append(" ms\n")
                .append("Triggers: crack=").append(session.crackTriggers.get())
                .append(", destroy=").append(session.destroyTriggers.get()).append('\n')
                .append("NOTE: timing buckets can overlap; do not sum them as exclusive CPU time.\n\n")
                .append("Operation-level timings:\n");

        appendMetric(report, "containing lookup", session.containingLookupCalls, session.containingLookupNanos);
        appendMetric(report, "Frame proximity scan", session.frameScanCalls, session.frameScanNanos);
        appendMetric(report, "crack route", session.crackCalls, session.crackNanos);
        appendMetric(report, "parent destroy route", session.destroyCalls, session.destroyNanos);
        appendMetric(report, "IClientBlockExtensions destroy hook", session.extensionCalls, session.extensionNanos);
        appendMetric(report, "VoxelShape lookup", session.shapeCalls, session.shapeNanos);
        appendMetric(report, "TerrainParticle construction", session.constructCalls, session.constructNanos);
        appendMetric(report, "ParticleEngine.add", session.addCalls, session.addNanos);
        appendMetric(report, "parent TerrainParticle light", session.lightCalls, session.lightNanos);
        appendMetric(report, "detached TerrainParticle tick", session.detachedTickCalls, session.detachedTickNanos);
        appendMetric(report, "vanilla parent collision", session.collisionCalls, session.collisionNanos);
        appendMetricWithMax(report, "GameRenderer.render", session.renderCalls, session.renderNanos, session.maxRenderNanos);
        appendMetricWithMax(report, "Minecraft.tick", session.clientTickCalls, session.clientTickNanos, session.maxClientTickNanos);

        report.append("\nRouting counters:\n")
                .append("  Sable care queries: ").append(session.sableCareQueries.get())
                .append(" (returned true=").append(session.sableCareTrue.get()).append(")\n")
                .append("  Unclassified TerrainParticle light calls while armed: ")
                .append(session.unclassifiedTerrainLightCalls.get()).append('\n');

        report.append("\nClient-thread sampler (every ~").append(SAMPLE_SLEEP_MILLIS).append(" ms):\n")
                .append("  samples=").append(totalSamples)
                .append(", RUNNABLE=").append(runnableSamples)
                .append(", WAITING=").append(waitingSamples)
                .append(", TIMED_WAITING=").append(timedWaitingSamples)
                .append(", BLOCKED=").append(blockedSamples)
                .append(", noStack=").append(noStackSamples).append('\n')
                .append("  RUNNABLE samples containing: Sable=").append(sableSamples)
                .append(", SableScale=").append(sableScaleSamples)
                .append(", Antikythera=").append(antikytheraSamples)
                .append(", particle=").append(particleSamples)
                .append(", renderer=").append(rendererSamples).append('\n');

        appendTop(report, "Top RUNNABLE leaf methods", leafSamples);
        appendTop(report, "Top interesting frames", interestingSamples);
        report.append("================ END ANTIKYTHERA CLIENT PERF SAMPLE ==================\n");
        AntikytheraMechanism.LOGGER.warn(report.toString());
    }

    private static void appendMetric(
            StringBuilder report,
            String label,
            AtomicLong calls,
            AtomicLong nanos) {
        long callCount = calls.get();
        long totalNanos = nanos.get();
        report.append("  ").append(label).append(": calls=").append(callCount)
                .append(", total=").append(formatNanos(totalNanos)).append(" ms");
        if (callCount > 0L) {
            report.append(", avg=").append(formatNanos(totalNanos / callCount)).append(" ms");
        }
        report.append('\n');
    }

    private static void appendMetricWithMax(
            StringBuilder report,
            String label,
            AtomicLong calls,
            AtomicLong nanos,
            AtomicLong maxNanos) {
        appendMetric(report, label, calls, nanos);
        report.append("    max=").append(formatNanos(maxNanos.get())).append(" ms\n");
    }

    private static void appendTop(StringBuilder report, String title, Map<String, Integer> samples) {
        report.append('\n').append(title).append(":\n");
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(samples.entrySet());
        ordered.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int rows = Math.min(TOP_SAMPLE_ROWS, ordered.size());
        if (rows == 0) {
            report.append("  <none>\n");
            return;
        }
        for (int i = 0; i < rows; i++) {
            Map.Entry<String, Integer> entry = ordered.get(i);
            report.append("  ").append(entry.getValue()).append("x  ").append(entry.getKey()).append('\n');
        }
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f", (double) nanos / 1_000_000.0);
    }

    private enum Counter {
        CRACK {
            @Override AtomicLong calls(Session s) { return s.crackCalls; }
            @Override AtomicLong nanos(Session s) { return s.crackNanos; }
        },
        DESTROY {
            @Override AtomicLong calls(Session s) { return s.destroyCalls; }
            @Override AtomicLong nanos(Session s) { return s.destroyNanos; }
        },
        EXTENSION {
            @Override AtomicLong calls(Session s) { return s.extensionCalls; }
            @Override AtomicLong nanos(Session s) { return s.extensionNanos; }
        },
        SHAPE {
            @Override AtomicLong calls(Session s) { return s.shapeCalls; }
            @Override AtomicLong nanos(Session s) { return s.shapeNanos; }
        },
        CONSTRUCT {
            @Override AtomicLong calls(Session s) { return s.constructCalls; }
            @Override AtomicLong nanos(Session s) { return s.constructNanos; }
        },
        ADD {
            @Override AtomicLong calls(Session s) { return s.addCalls; }
            @Override AtomicLong nanos(Session s) { return s.addNanos; }
        },
        LIGHT {
            @Override AtomicLong calls(Session s) { return s.lightCalls; }
            @Override AtomicLong nanos(Session s) { return s.lightNanos; }
        },
        DETACHED_TICK {
            @Override AtomicLong calls(Session s) { return s.detachedTickCalls; }
            @Override AtomicLong nanos(Session s) { return s.detachedTickNanos; }
        },
        COLLISION {
            @Override AtomicLong calls(Session s) { return s.collisionCalls; }
            @Override AtomicLong nanos(Session s) { return s.collisionNanos; }
        };

        abstract AtomicLong calls(Session session);
        abstract AtomicLong nanos(Session session);
    }

    private static final class Session {
        private final long id;
        private final Thread clientThread;
        private final BlockPos sourcePos;
        private final long startedNanos;
        private volatile long deadlineNanos;

        private final AtomicLong crackTriggers = new AtomicLong();
        private final AtomicLong destroyTriggers = new AtomicLong();
        private final AtomicLong containingLookupCalls = new AtomicLong();
        private final AtomicLong containingLookupNanos = new AtomicLong();
        private final AtomicLong frameScanCalls = new AtomicLong();
        private final AtomicLong frameScanNanos = new AtomicLong();
        private final AtomicLong crackCalls = new AtomicLong();
        private final AtomicLong crackNanos = new AtomicLong();
        private final AtomicLong destroyCalls = new AtomicLong();
        private final AtomicLong destroyNanos = new AtomicLong();
        private final AtomicLong extensionCalls = new AtomicLong();
        private final AtomicLong extensionNanos = new AtomicLong();
        private final AtomicLong shapeCalls = new AtomicLong();
        private final AtomicLong shapeNanos = new AtomicLong();
        private final AtomicLong constructCalls = new AtomicLong();
        private final AtomicLong constructNanos = new AtomicLong();
        private final AtomicLong addCalls = new AtomicLong();
        private final AtomicLong addNanos = new AtomicLong();
        private final AtomicLong lightCalls = new AtomicLong();
        private final AtomicLong lightNanos = new AtomicLong();
        private final AtomicLong detachedTickCalls = new AtomicLong();
        private final AtomicLong detachedTickNanos = new AtomicLong();
        private final AtomicLong collisionCalls = new AtomicLong();
        private final AtomicLong collisionNanos = new AtomicLong();
        private final AtomicLong renderCalls = new AtomicLong();
        private final AtomicLong renderNanos = new AtomicLong();
        private final AtomicLong maxRenderNanos = new AtomicLong();
        private final AtomicLong clientTickCalls = new AtomicLong();
        private final AtomicLong clientTickNanos = new AtomicLong();
        private final AtomicLong maxClientTickNanos = new AtomicLong();
        private final AtomicLong sableCareQueries = new AtomicLong();
        private final AtomicLong sableCareTrue = new AtomicLong();
        private final AtomicLong unclassifiedTerrainLightCalls = new AtomicLong();

        private Session(long id, Thread clientThread, BlockPos sourcePos, long startedNanos, long deadlineNanos) {
            this.id = id;
            this.clientThread = clientThread;
            this.sourcePos = sourcePos;
            this.startedNanos = startedNanos;
            this.deadlineNanos = deadlineNanos;
        }

        private void noteTrigger(String trigger, long now) {
            if (trigger.contains("destroy")) {
                destroyTriggers.incrementAndGet();
                long desiredDeadline = now + POST_DESTROY_WINDOW_NANOS;
                long absoluteMaximum = startedNanos + MAX_WINDOW_NANOS;
                deadlineNanos = Math.min(absoluteMaximum, Math.max(deadlineNanos, desiredDeadline));
            } else {
                crackTriggers.incrementAndGet();
            }
        }
    }
}
