package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.FrameOrientation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronous structural view of Create placement targets while {@code Contraption#addBlocksToWorld}
 * is still between its persisted prepare and commit points.
 *
 * <p>The durable {@code PendingContraptionMove} deliberately leaves {@code frameIndex} and the
 * assembly's logical origin/orientation untouched until every destination Frame has actually been
 * placed. Vanilla neighbour updates, however, can ask a just-written destination Frame whether it is
 * sturdy before that final commit. This context exposes only the already-validated target mapping for
 * that narrow call window; it does not mutate persistent assembly ownership and it is always removed
 * by a surrounding try/finally in the Create mixin.</p>
 *
 * <p>This class intentionally contains no Create types so loading the Antikythera core without Create
 * installed remains safe.</p>
 */
public final class CreateAssemblyPlacementContext {
    private static final ThreadLocal<ArrayDeque<Context>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private CreateAssemblyPlacementContext() {
    }

    public record Target(
            UUID assemblyId,
            FrameOrientation orientation,
            BlockPos logicalFrameOffset,
            Set<BlockPos> targetFrames) {
        public Target {
            logicalFrameOffset = logicalFrameOffset.immutable();
            targetFrames = Set.copyOf(targetFrames);
        }
    }

    /**
     * Pushes a target view after {@code MechanismAssemblyManager.prepareContraptionPlacement} has
     * durably accepted the exact same maps.
     */
    public static void begin(
            ServerLevel level,
            Map<UUID, ? extends Collection<BlockPos>> targetFrames,
            Map<UUID, BlockPos> targetOrigins,
            Map<UUID, AssemblyPose> finalPoses) {
        Map<BlockPos, Target> targetsByPosition = new HashMap<>();
        for (Map.Entry<UUID, ? extends Collection<BlockPos>> entry : targetFrames.entrySet()) {
            UUID assemblyId = entry.getKey();
            BlockPos targetOrigin = targetOrigins.get(assemblyId);
            AssemblyPose finalPose = finalPoses.get(assemblyId);
            if (targetOrigin == null || finalPose == null) {
                throw new IllegalStateException("Prepared Create placement is missing target metadata for " + assemblyId);
            }
            FrameOrientation orientation = FrameOrientation.fromQuaternion(
                            finalPose.orientation(new org.joml.Quaterniond()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Prepared Create placement has non-orthogonal orientation for " + assemblyId));
            Set<BlockPos> frames = new HashSet<>();
            entry.getValue().forEach(frame -> frames.add(frame.immutable()));
            Set<BlockPos> immutableFrames = Collections.unmodifiableSet(frames);
            for (BlockPos frame : immutableFrames) {
                BlockPos logicalOffset = orientation.toLogical(frame.subtract(targetOrigin));
                Target previous = targetsByPosition.put(
                        frame,
                        new Target(assemblyId, orientation, logicalOffset, immutableFrames));
                if (previous != null) {
                    throw new IllegalStateException("Prepared Create placements overlap at " + frame);
                }
            }
        }
        STACK.get().push(new Context(level, Collections.unmodifiableMap(targetsByPosition)));
    }

    /** Returns the prepared target mapping for a destination Frame in the current synchronous call. */
    public static Target targetAt(ServerLevel level, BlockPos position) {
        for (Context context : STACK.get()) {
            if (context.level == level) {
                Target target = context.targetsByPosition.get(position);
                if (target != null) {
                    return target;
                }
            }
        }
        return null;
    }

    /** Marker used by the Create method wrapper so nested calls can be unwound exactly. */
    public static int depth() {
        return STACK.get().size();
    }

    /** Restores the stack to the marker captured before one Create placement call. */
    public static void restoreDepth(int depth) {
        ArrayDeque<Context> stack = STACK.get();
        while (stack.size() > depth) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    private record Context(ServerLevel level, Map<BlockPos, Target> targetsByPosition) {
    }
}
