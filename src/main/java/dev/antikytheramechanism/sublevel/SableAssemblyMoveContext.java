package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

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
        Set<Long> sourceChunks = new HashSet<>();
        Set<Long> targetChunks = new HashSet<>();
        Map<BlockPos, BlockPos> targetsBySource = new HashMap<>();
        Map<BlockPos, BlockPos> sourcesByTarget = new HashMap<>();
        Map<BlockPos, BlockState> sourceStates = new HashMap<>();
        for (BlockPos source : sourceBlocks) {
            BlockPos immutableSource = source.immutable();
            BlockPos target = transform.apply(immutableSource).immutable();
            sources.add(immutableSource);
            sourceChunks.add(ChunkPos.asLong(immutableSource.getX() >> 4, immutableSource.getZ() >> 4));
            targetChunks.add(ChunkPos.asLong(target.getX() >> 4, target.getZ() >> 4));
            targetsBySource.put(immutableSource, target);
            sourcesByTarget.put(target, immutableSource);
            sourceStates.put(immutableSource, sourceLevel.getBlockState(immutableSource));
        }

        Context context = new Context(
                sourceLevel,
                transform.getLevel(),
                Collections.unmodifiableSet(sources),
                Collections.unmodifiableSet(sourceChunks),
                Collections.unmodifiableSet(targetChunks),
                Collections.unmodifiableMap(targetsBySource),
                Collections.unmodifiableMap(sourcesByTarget),
                Collections.unmodifiableMap(sourceStates));

        // Capture macro <- mini face support before Sable writes or clears the first block. The
        // context is deliberately not on STACK yet, so FrameFaceSupport performs an ordinary live
        // query against the coherent source state rather than reading the snapshot being built.
        context.captureFrameFaceSupport();
        STACK.get().push(context);
    }

    /**
     * Ends one atomic Sable move and replays mini -> macro boundary notifications only after its
     * NeoForge block-snapshot capture window has closed.
     *
     * <p>Sable 2.0.3 implements its NeoForge {@code ignoreOnPlace} phase by setting
     * {@code Level.captureBlockSnapshots}. A support-dependent macro block changed from inside that
     * phase is mutated on the server, but NeoForge deliberately skips markAndNotifyBlock, including
     * the client block-update packet. Sable later resends only the mini positions it moved, leaving
     * that macro block as a client-side ghost. Writes selected by this exact move therefore collect
     * their owning Frames and emit the normal neighbour update after the outermost move returns.</p>
     */
    public static void end() {
        ArrayDeque<Context> stack = STACK.get();
        if (stack.isEmpty()) {
            return;
        }

        Context finished = stack.pop();
        if (!stack.isEmpty()) {
            // A nested move is still inside an outer Sable atomic operation. Keep waiting rather
            // than replaying into an outer captureBlockSnapshots window.
            finished.mergeDeferredParentNotificationsInto(stack.peek());
            return;
        }

        STACK.remove();
        finished.replayDeferredParentNotifications();
    }

    /**
     * True only for a source or target chunk selected by the active Sable move.
     *
     * <p>Sable 2.0.3's {@code LevelAccelerator} can bypass {@code Level#getChunk} and therefore
     * bypass Sable's plot routing. Chunk keys are captured once at move start so the routing guard
     * remains O(1) even for large assemblies.</p>
     */
    public static boolean isMovedChunk(ServerLevel level, int chunkX, int chunkZ) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        for (Context context : STACK.get()) {
            if (context.sourceLevel == level && context.sourceChunks.contains(chunkKey)) {
                return true;
            }
            if (context.targetLevel == level && context.targetChunks.contains(chunkKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Defers the parent Frame neighbour notification for an actual managed mini position selected by
     * the active Sable move. Returns false for ordinary writes and for detached/foreign SubLevels.
     */
    public static boolean deferManagedParentNotification(
            ServerLevel level,
            BlockPos globalPlotPosition) {
        Context context = findMovedPositionContext(level, globalPlotPosition);
        if (context == null) {
            return false;
        }

        var containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return false;
        }
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return false;
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return false;
        }
        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
            return false;
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition).immutable();
        context.deferredParentNotifications
                .computeIfAbsent(level, ignored -> new LinkedHashSet<>())
                .add(framePosition);
        return true;
    }

    /**
     * Returns the pre-move state for a selected block at either endpoint of the active Sable move.
     * The snapshot exists only while moveBlocks is active and cannot become a permanent shell.
     */
    public static @Nullable BlockState frozenMovedBlockState(ServerLevel level, BlockPos position) {
        for (Context context : STACK.get()) {
            BlockPos source = null;
            if (context.sourceLevel == level && context.sourceBlocks.contains(position)) source = position;
            else if (context.targetLevel == level) source = context.sourcesByTarget.get(position);
            if (source != null) return context.sourceStates.get(source);
        }
        return null;
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

    private static @Nullable Context findMovedPositionContext(
            ServerLevel level,
            BlockPos position) {
        ArrayDeque<Context> stack = STACK.get();
        for (Context context : stack) {
            if (context.sourceLevel == level && context.sourceBlocks.contains(position)) {
                return context;
            }
            if (context.targetLevel == level && context.sourcesByTarget.containsKey(position)) {
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
        private final Set<Long> sourceChunks;
        private final Set<Long> targetChunks;
        private final Map<BlockPos, BlockPos> targetsBySource;
        private final Map<BlockPos, BlockPos> sourcesByTarget;
        private final Map<BlockPos, BlockState> sourceStates;
        private final Map<BlockPos, Double> frozenFrameMassBySource = new HashMap<>();
        private final Map<BlockPos, Double> frozenFrameMassByTarget = new HashMap<>();
        private final Map<FaceSupportKey, Boolean> frozenFrameFaceSupportBySource = new HashMap<>();
        private final Map<FaceSupportKey, Boolean> frozenFrameFaceSupportByTarget = new HashMap<>();
        private final Map<ServerLevel, Set<BlockPos>> deferredParentNotifications = new HashMap<>();

        private Context(
                ServerLevel sourceLevel,
                ServerLevel targetLevel,
                Set<BlockPos> sourceBlocks,
                Set<Long> sourceChunks,
                Set<Long> targetChunks,
                Map<BlockPos, BlockPos> targetsBySource,
                Map<BlockPos, BlockPos> sourcesByTarget,
                Map<BlockPos, BlockState> sourceStates) {
            this.sourceLevel = sourceLevel;
            this.targetLevel = targetLevel;
            this.sourceBlocks = sourceBlocks;
            this.sourceChunks = sourceChunks;
            this.targetChunks = targetChunks;
            this.targetsBySource = targetsBySource;
            this.sourcesByTarget = sourcesByTarget;
            this.sourceStates = sourceStates;
        }

        private void mergeDeferredParentNotificationsInto(Context parent) {
            deferredParentNotifications.forEach((level, positions) ->
                    parent.deferredParentNotifications
                            .computeIfAbsent(level, ignored -> new LinkedHashSet<>())
                            .addAll(positions));
        }

        private void replayDeferredParentNotifications() {
            deferredParentNotifications.forEach((level, framePositions) -> {
                Runnable replay = () -> {
                    for (BlockPos framePosition : framePositions) {
                        BlockState frameState = level.getBlockState(framePosition);
                        if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
                            continue;
                        }
                        // This is intentionally the same normal vanilla notification performed by
                        // RedstoneBoundaryBridge for an ordinary managed mini write. Running it after
                        // Sable's snapshot window lets support-dependent macro removals use Level's
                        // regular markAndNotifyBlock path, including UPDATE_CLIENTS.
                        frameState.updateNeighbourShapes(level, framePosition, Block.UPDATE_ALL);
                        level.updateNeighborsAt(framePosition, ModRegistries.MECHANISM_FRAME.get());
                    }
                };

                if (level.captureBlockSnapshots) {
                    // Sable normally clears this flag before moveBlocks returns. Keep a fail-safe for
                    // nested/foreign snapshot owners rather than recreating the original ghost-state
                    // bug if another capture window is still active.
                    AntikytheraMechanism.LOGGER.debug(
                            "Deferring {} managed Frame boundary notifications one server task because block snapshots are still being captured",
                            framePositions.size());
                    level.getServer().execute(replay);
                } else {
                    replay.run();
                }
            });
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
