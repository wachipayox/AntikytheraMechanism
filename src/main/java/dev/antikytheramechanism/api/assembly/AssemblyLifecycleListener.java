package dev.antikytheramechanism.api.assembly;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;

/**
 * Optional, server-side integration points around destructive assembly operations.
 *
 * <p>Listeners must not load chunks. A {@code false} return value vetoes a preflight or reports
 * that a post/rollback action could not be completed safely. The dispatcher also treats listener
 * exceptions as failures, so optional integrations fail closed instead of allowing stale external
 * state to survive a content move.</p>
 */
public interface AssemblyLifecycleListener {
    default boolean beforeAssemblyTransfer(AssemblyTransferContext context) {
        return true;
    }

    default boolean afterAssemblyTransfer(AssemblyTransferContext context) {
        return true;
    }

    default boolean onAssemblyTransferRollback(
            AssemblyTransferContext context,
            boolean contentRestored) {
        return true;
    }

    default boolean beforeFrameEvacuation(FrameEvacuationContext context) {
        return true;
    }

    default boolean afterFrameEvacuation(FrameEvacuationContext context) {
        return true;
    }

    default boolean onFrameEvacuationRollback(
            FrameEvacuationContext context,
            boolean contentRestored) {
        return true;
    }

    /** Called after the manager has committed its authoritative frame ownership metadata. */
    default boolean afterFrameGraphChanged(FrameGraphChangeContext context) {
        return true;
    }

    enum TransferKind {
        TRANSFER,
        MERGE,
        SPLIT
    }

    enum EvacuationReason {
        PLAYER,
        EXPLOSION,
        GENERIC
    }

    enum FrameGraphChangeKind {
        FRAME_ADDED,
        FRAME_REMOVED,
        MERGE,
        SPLIT
    }

    record AssemblyTransferContext(
            ServerLevel level,
            MechanismAssembly source,
            MechanismAssembly target,
            List<BlockPos> frames,
            TransferKind kind) {
        public AssemblyTransferContext {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            frames = List.copyOf(frames);
            Objects.requireNonNull(kind, "kind");
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("An assembly transfer must include at least one frame");
            }
        }
    }

    record FrameEvacuationContext(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePosition,
            EvacuationReason reason) {
        public FrameEvacuationContext {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(assembly, "assembly");
            framePosition = framePosition.immutable();
            Objects.requireNonNull(reason, "reason");
        }
    }

    record FrameGraphChangeContext(
            ServerLevel level,
            FrameGraphChangeKind kind,
            MechanismAssembly primary,
            MechanismAssembly secondary,
            List<BlockPos> changedFrames) {
        public FrameGraphChangeContext {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(primary, "primary");
            changedFrames = List.copyOf(changedFrames);
        }
    }
}
