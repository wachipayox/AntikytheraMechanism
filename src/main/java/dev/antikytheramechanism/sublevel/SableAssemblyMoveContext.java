package dev.antikytheramechanism.sublevel;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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
 * this context only while Sable is inside {@code moveBlocks}: to freeze structural boundary states
 * and to give Sable one stable effective Frame mass at both the source and destination positions.</p>
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
        STACK.get().push(new Context(
                sourceLevel,
                transform.getLevel(),
                Collections.unmodifiableSet(sources),
                Collections.unmodifiableMap(targetsBySource),
                Collections.unmodifiableMap(sourcesByTarget)));
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

    private static Context findSourceContext(ServerLevel level) {
        ArrayDeque<Context> stack = STACK.get();
        for (Context context : stack) {
            if (context.sourceLevel == level) {
                return context;
            }
        }
        return null;
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
    }
}
