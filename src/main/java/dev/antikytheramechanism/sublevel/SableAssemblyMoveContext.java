package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Synchronous context for one complete Sable {@code SubLevelAssemblyHelper.moveBlocks} operation.
 *
 * <p>Sable exposes per-block listener callbacks, but those callbacks otherwise lose the fact that
 * neighbouring parent blocks are part of the same atomic assembly/disassembly. Antikythera uses
 * this context only while Sable is inside {@code moveBlocks}: to freeze structural boundary states,
 * macro support supplied by mini faces, and the stable effective Frame mass at both endpoints.</p>
 */
public final class SableAssemblyMoveContext {
    private static final ThreadLocal<ArrayDeque<Context>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private SableAssemblyMoveContext() {
    }

    public static void begin(
            ServerLevel sourceLevel,
            AssemblyTransform transform,
            Iterable<BlockPos> sourceBlocks) {
        Set<BlockPos> sources = new HashSet<>();
        Map<BlockPos, BlockPos> targetsBySource = new HashMap<>();
        Map<BlockPos, BlockPos> sourcesByTarget = new HashMap<>();
        for (BlockPos source : sourceBlocks) {
            BlockPos immutableSource = source.immutable();
            BlockPos target = transform.apply(immutableSource).immutable();
            sources.add(immutableSource);
            targetsBySource.put(immutableSource, target);
            sourcesByTarget.put(target, immutableSource);
        }

        Context context = new Context(
                sourceLevel,
                transform.getLevel(),
                Collections.unmodifiableSet(sources),
                Collections.unmodifiableMap(targetsBySource),
                Collections.unmodifiableMap(sourcesByTarget));

        // Capture macro <- mini face support before Sable writes or clears the first block. The
        // context is deliberately not on STACK yet, so FrameFaceSupport performs an ordinary live
        // query against the coherent source state rather than reading the snapshot being built.
        context.captureFrameFaceSupport();
        STACK.get().push(context);
    }

    public static void end() {
        ArrayDeque<Context> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /** Source positions selected by Sable for the current complete move. */
    public static Set<BlockPos> sourceBlocks(ServerLevel level) {
        Context context = findSourceContext(level);
        return context == null ? Set.of() : context.sourceBlocks;
    }

    /**
     * Freezes one Frame's already-resolved effective mass for both ends of the current Sable move.
     * This prevents Sable's block-change callbacks from re-reading mini content after assembly
     * metadata has moved to the other endpoint.
     */
    public static void freezeFrameMass(ServerLevel sourceLevel, BlockPos sourceFrame, double mass) {
        Context context = findSourceContext(sourceLevel);
        if (context == null) {
            return;
        }
        BlockPos source = sourceFrame.immutable();
        BlockPos target = context.targetsBySource.get(source);
        if (target == null) {
            return;
        }
        context.frozenFrameMassBySource.put(source, mass);
        context.frozenFrameMassByTarget.put(target, mass);
    }

    /** Stable effective mass for a relocating Frame, if the queried position belongs to this move. */
    public static OptionalDouble frozenFrameMass(ServerLevel level, BlockPos position) {
        ArrayDeque<Context> stack = STACK.get();
        for (Context context : stack) {
            Double value = null;
            if (context.sourceLevel == level) {
                value = context.frozenFrameMassBySource.get(position);
            }
            if (value == null && context.targetLevel == level) {
                value = context.frozenFrameMassByTarget.get(position);
            }
            if (value != null) {
                return OptionalDouble.of(value);
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * Returns the pre-move mini-backed support value for a Frame face while both the Frame and the
     * macro block attached to that face are being relocated by the same Sable move.
     *
     * <p>This deliberately also works after the source Frame has already been replaced with AIR and
     * before the destination Frame is completely adopted. It is an atomic-operation snapshot, not a
     * general virtual block projection.</p>
     */
    public static @Nullable Boolean frozenFrameFaceSupport(
            ServerLevel level,
            BlockPos position,
            Direction face,
            SupportType supportType) {
        FaceSupportKey key = new FaceSupportKey(position, face, supportType);
        ArrayDeque<Context> stack = STACK.get();
        for (Context context : stack) {
            Boolean value = null;
            if (context.sourceLevel == level) {
                value = context.frozenFrameFaceSupportBySource.get(key);
            }
            if (value == null && context.targetLevel == level) {
                value = context.frozenFrameFaceSupportByTarget.get(key);
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Context findSourceContext(ServerLevel level) {
        ArrayDeque<Context> stack = STACK.get();
        for (Context context : stack) {
            if (context.sourceLevel == level) {
                return context;
            }
        }
        return null;
    }

    private static @Nullable Direction directionBetween(BlockPos from, BlockPos to) {
        for (Direction direction : Direction.values()) {
            if (from.relative(direction).equals(to)) {
                return direction;
            }
        }
        return null;
    }

    private record FaceSupportKey(
            BlockPos position,
            Direction face,
            SupportType supportType) {
        private FaceSupportKey {
            position = position.immutable();
        }
    }

    private static final class Context {
        private final ServerLevel sourceLevel;
        private final ServerLevel targetLevel;
        private final Set<BlockPos> sourceBlocks;
        private final Map<BlockPos, BlockPos> targetsBySource;
        @SuppressWarnings("unused")
        private final Map<BlockPos, BlockPos> sourcesByTarget;
        private final Map<BlockPos, Double> frozenFrameMassBySource = new HashMap<>();
        private final Map<BlockPos, Double> frozenFrameMassByTarget = new HashMap<>();
        private final Map<FaceSupportKey, Boolean> frozenFrameFaceSupportBySource = new HashMap<>();
        private final Map<FaceSupportKey, Boolean> frozenFrameFaceSupportByTarget = new HashMap<>();

        private Context(
                ServerLevel sourceLevel,
                ServerLevel targetLevel,
                Set<BlockPos> sourceBlocks,
                Map<BlockPos, BlockPos> targetsBySource,
                Map<BlockPos, BlockPos> sourcesByTarget) {
            this.sourceLevel = sourceLevel;
            this.targetLevel = targetLevel;
            this.sourceBlocks = sourceBlocks;
            this.targetsBySource = targetsBySource;
            this.sourcesByTarget = sourcesByTarget;
        }

        private void captureFrameFaceSupport() {
            for (BlockPos sourceFrame : sourceBlocks) {
                BlockState frameState = sourceLevel.getBlockState(sourceFrame);
                if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
                    continue;
                }

                BlockPos targetFrame = targetsBySource.get(sourceFrame);
                if (targetFrame == null) {
                    continue;
                }

                for (Direction sourceFace : Direction.values()) {
                    BlockPos sourceDependent = sourceFrame.relative(sourceFace);
                    if (!sourceBlocks.contains(sourceDependent)) {
                        continue;
                    }

                    // Freeze support only for an actual non-Frame macro block travelling beside the
                    // Frame. A stationary neighbour must still observe the Frame disappearing, and
                    // sibling Frames are continuous mini space rather than supported attachments.
                    BlockState dependentState = sourceLevel.getBlockState(sourceDependent);
                    if (dependentState.isAir()
                            || dependentState.is(ModRegistries.MECHANISM_FRAME.get())) {
                        continue;
                    }

                    BlockPos targetDependent = targetsBySource.get(sourceDependent);
                    if (targetDependent == null) {
                        continue;
                    }
                    Direction targetFace = directionBetween(targetFrame, targetDependent);
                    if (targetFace == null) {
                        continue;
                    }

                    for (SupportType supportType : SupportType.values()) {
                        Boolean sturdy = FrameFaceSupport.query(
                                sourceLevel,
                                sourceFrame,
                                sourceFace,
                                supportType);
                        if (sturdy == null) {
                            continue;
                        }
                        frozenFrameFaceSupportBySource.put(
                                new FaceSupportKey(sourceFrame, sourceFace, supportType),
                                sturdy);
                        frozenFrameFaceSupportByTarget.put(
                                new FaceSupportKey(targetFrame, targetFace, supportType),
                                sturdy);
                    }
                }
            }
        }
    }
}
