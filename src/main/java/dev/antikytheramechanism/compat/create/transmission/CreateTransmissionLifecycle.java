package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleEvents;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;

import java.util.concurrent.atomic.AtomicBoolean;

/** Clears Create's global source/network fields around transactional mini-content mutations. */
public final class CreateTransmissionLifecycle implements AssemblyLifecycleListener {
    private static final CreateTransmissionLifecycle INSTANCE = new CreateTransmissionLifecycle();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private CreateTransmissionLifecycle() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            AssemblyLifecycleEvents.register(
                    AntikytheraMechanism.id("create_kinetic_lifecycle"), INSTANCE);
        }
    }

    @Override
    public boolean beforeAssemblyTransfer(AssemblyTransferContext context) {
        TransmissionLinkCoordinator.quiesceAssembly(context.level(), context.source());
        TransmissionLinkCoordinator.quiesceAssembly(context.level(), context.target());
        return true;
    }

    @Override
    public boolean afterAssemblyTransfer(AssemblyTransferContext context) {
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.source());
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.target());
        return true;
    }

    @Override
    public boolean onAssemblyTransferRollback(AssemblyTransferContext context, boolean contentRestored) {
        if (!contentRestored) {
            return false;
        }
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.source());
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.target());
        return true;
    }

    @Override
    public boolean beforeFrameEvacuation(FrameEvacuationContext context) {
        TransmissionLinkCoordinator.quiesceAssembly(context.level(), context.assembly());
        return true;
    }

    @Override
    public boolean afterFrameEvacuation(FrameEvacuationContext context) {
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.assembly());
        return true;
    }

    @Override
    public boolean onFrameEvacuationRollback(FrameEvacuationContext context, boolean contentRestored) {
        if (!contentRestored) {
            return false;
        }
        TransmissionLinkCoordinator.rebuildAssemblyKinetics(context.level(), context.assembly());
        return true;
    }
}
