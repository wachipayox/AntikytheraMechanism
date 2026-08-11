package dev.antikytheramechanism.api.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Process-local registry for optional assembly lifecycle adapters. */
public final class AssemblyLifecycleEvents {
    private static final Map<ResourceLocation, AssemblyLifecycleListener> LISTENERS = new LinkedHashMap<>();

    private AssemblyLifecycleEvents() {
    }

    /**
     * Registers one stable listener id. Re-registering the same instance is idempotent; replacing
     * an id with a different listener is rejected so integrations cannot silently shadow each other.
     */
    public static synchronized Registration register(
            ResourceLocation id,
            AssemblyLifecycleListener listener) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listener, "listener");
        AssemblyLifecycleListener existing = LISTENERS.get(id);
        if (existing != null && existing != listener) {
            throw new IllegalStateException("Assembly lifecycle listener id is already registered: " + id);
        }
        LISTENERS.putIfAbsent(id, listener);
        return new Registration(id, listener);
    }

    public static TransferTransaction beginTransfer(
            AssemblyLifecycleListener.AssemblyTransferContext context) {
        Objects.requireNonNull(context, "context");
        List<RegisteredListener> prepared = new ArrayList<>();
        for (RegisteredListener registered : snapshot()) {
            // Include the current listener before invoking it: a listener that mutates state and
            // then vetoes or throws still receives the compensating rollback callback.
            prepared.add(registered);
            if (!invoke(
                    registered,
                    "beforeAssemblyTransfer",
                    () -> registered.listener().beforeAssemblyTransfer(context))) {
                boolean compensated = rollbackTransfer(prepared, context, true);
                return TransferTransaction.rejected(context, prepared, compensated);
            }
        }
        return TransferTransaction.approved(context, prepared);
    }

    public static EvacuationTransaction beginEvacuation(
            AssemblyLifecycleListener.FrameEvacuationContext context) {
        Objects.requireNonNull(context, "context");
        List<RegisteredListener> prepared = new ArrayList<>();
        for (RegisteredListener registered : snapshot()) {
            prepared.add(registered);
            if (!invoke(
                    registered,
                    "beforeFrameEvacuation",
                    () -> registered.listener().beforeFrameEvacuation(context))) {
                boolean compensated = rollbackEvacuation(prepared, context, true);
                return EvacuationTransaction.rejected(context, prepared, compensated);
            }
        }
        return EvacuationTransaction.approved(context, prepared);
    }

    public static boolean afterFrameGraphChanged(
            AssemblyLifecycleListener.FrameGraphChangeContext context) {
        boolean success = true;
        for (RegisteredListener registered : snapshot()) {
            success &= invoke(
                    registered,
                    "afterFrameGraphChanged",
                    () -> registered.listener().afterFrameGraphChanged(context));
        }
        return success;
    }

    private static synchronized List<RegisteredListener> snapshot() {
        return LISTENERS.entrySet().stream()
                .map(entry -> new RegisteredListener(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean rollbackTransfer(
            List<RegisteredListener> listeners,
            AssemblyLifecycleListener.AssemblyTransferContext context,
            boolean contentRestored) {
        boolean success = true;
        List<RegisteredListener> reverse = new ArrayList<>(listeners);
        Collections.reverse(reverse);
        for (RegisteredListener registered : reverse) {
            success &= invoke(
                    registered,
                    "onAssemblyTransferRollback",
                    () -> registered.listener().onAssemblyTransferRollback(context, contentRestored));
        }
        return success;
    }

    private static boolean rollbackEvacuation(
            List<RegisteredListener> listeners,
            AssemblyLifecycleListener.FrameEvacuationContext context,
            boolean contentRestored) {
        boolean success = true;
        List<RegisteredListener> reverse = new ArrayList<>(listeners);
        Collections.reverse(reverse);
        for (RegisteredListener registered : reverse) {
            success &= invoke(
                    registered,
                    "onFrameEvacuationRollback",
                    () -> registered.listener().onFrameEvacuationRollback(context, contentRestored));
        }
        return success;
    }

    private static boolean invoke(RegisteredListener registered, String hook, HookCall call) {
        try {
            boolean accepted = call.invoke();
            if (!accepted) {
                AntikytheraMechanism.LOGGER.error(
                        "Assembly lifecycle listener {} rejected {}",
                        registered.id(),
                        hook);
            }
            return accepted;
        } catch (RuntimeException | LinkageError exception) {
            AntikytheraMechanism.LOGGER.error(
                    "Assembly lifecycle listener {} failed during {}; operation will fail closed",
                    registered.id(),
                    hook,
                    exception);
            return false;
        }
    }

    public static final class TransferTransaction {
        private final AssemblyLifecycleListener.AssemblyTransferContext context;
        private final List<RegisteredListener> listeners;
        private final boolean approved;
        private final boolean rejectionCompensated;
        private boolean finished;

        private TransferTransaction(
                AssemblyLifecycleListener.AssemblyTransferContext context,
                List<RegisteredListener> listeners,
                boolean approved,
                boolean rejectionCompensated) {
            this.context = context;
            this.listeners = List.copyOf(listeners);
            this.approved = approved;
            this.rejectionCompensated = rejectionCompensated;
        }

        private static TransferTransaction approved(
                AssemblyLifecycleListener.AssemblyTransferContext context,
                List<RegisteredListener> listeners) {
            return new TransferTransaction(context, listeners, true, true);
        }

        private static TransferTransaction rejected(
                AssemblyLifecycleListener.AssemblyTransferContext context,
                List<RegisteredListener> listeners,
                boolean compensated) {
            return new TransferTransaction(context, listeners, false, compensated);
        }

        public boolean approved() {
            return approved;
        }

        public boolean rejectionCompensated() {
            return rejectionCompensated;
        }

        /** Runs post-transfer hooks once. A false result requires content rollback. */
        public boolean complete() {
            if (!approved || finished) {
                return false;
            }
            boolean success = true;
            for (RegisteredListener registered : listeners) {
                success &= invoke(
                        registered,
                        "afterAssemblyTransfer",
                        () -> registered.listener().afterAssemblyTransfer(context));
            }
            if (success) {
                finished = true;
            }
            return success;
        }

        /** Runs compensating hooks once, in reverse registration order. */
        public boolean rollback(boolean contentRestored) {
            if (!approved || finished) {
                return !approved && rejectionCompensated;
            }
            finished = true;
            return rollbackTransfer(listeners, context, contentRestored);
        }
    }

    public static final class EvacuationTransaction {
        private final AssemblyLifecycleListener.FrameEvacuationContext context;
        private final List<RegisteredListener> listeners;
        private final boolean approved;
        private final boolean rejectionCompensated;
        private boolean finished;

        private EvacuationTransaction(
                AssemblyLifecycleListener.FrameEvacuationContext context,
                List<RegisteredListener> listeners,
                boolean approved,
                boolean rejectionCompensated) {
            this.context = context;
            this.listeners = List.copyOf(listeners);
            this.approved = approved;
            this.rejectionCompensated = rejectionCompensated;
        }

        private static EvacuationTransaction approved(
                AssemblyLifecycleListener.FrameEvacuationContext context,
                List<RegisteredListener> listeners) {
            return new EvacuationTransaction(context, listeners, true, true);
        }

        private static EvacuationTransaction rejected(
                AssemblyLifecycleListener.FrameEvacuationContext context,
                List<RegisteredListener> listeners,
                boolean compensated) {
            return new EvacuationTransaction(context, listeners, false, compensated);
        }

        public boolean approved() {
            return approved;
        }

        public boolean rejectionCompensated() {
            return rejectionCompensated;
        }

        public boolean complete() {
            if (!approved || finished) {
                return false;
            }
            boolean success = true;
            for (RegisteredListener registered : listeners) {
                success &= invoke(
                        registered,
                        "afterFrameEvacuation",
                        () -> registered.listener().afterFrameEvacuation(context));
            }
            if (success) {
                finished = true;
            }
            return success;
        }

        public boolean rollback(boolean contentRestored) {
            if (!approved || finished) {
                return !approved && rejectionCompensated;
            }
            finished = true;
            return rollbackEvacuation(listeners, context, contentRestored);
        }
    }

    public static final class Registration implements AutoCloseable {
        private final ResourceLocation id;
        private final AssemblyLifecycleListener listener;
        private boolean closed;

        private Registration(ResourceLocation id, AssemblyLifecycleListener listener) {
            this.id = id;
            this.listener = listener;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            synchronized (AssemblyLifecycleEvents.class) {
                LISTENERS.remove(id, listener);
            }
            closed = true;
        }
    }

    private record RegisteredListener(ResourceLocation id, AssemblyLifecycleListener listener) {
    }

    @FunctionalInterface
    private interface HookCall {
        boolean invoke();
    }
}
