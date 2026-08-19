package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent identity for parent positions that an in-flight Create capture has physically vacated
 * and a later, unrelated Mechanism Frame has legitimately reused.
 *
 * <p>The moving assembly deliberately keeps its immutable source coordinates in
 * {@link PendingContraptionMove}; they are needed for crash recovery and rigid placement mapping.
 * Once the physical source position is reused, however, {@code frameIndex} must belong to the new
 * Frame. This marker tells recovery code that the old source claim was intentionally released rather
 * than corrupted.</p>
 */
public final class ContraptionSourceRelease {
    public static final String RELEASED_SOURCE_FRAMES_TAG = "released_source_frames";
    private static final String SOURCE_FRAMES_TAG = "source_frames";

    private ContraptionSourceRelease() {
    }

    public static Set<BlockPos> releasedSources(PendingContraptionMove move) {
        return ((PendingContraptionMoveReleaseAccess) (Object) move)
                .antikytheramechanism$getReleasedSourceFrames();
    }

    public static boolean isReleased(PendingContraptionMove move, BlockPos source) {
        return releasedSources(move).contains(source);
    }

    public static boolean release(PendingContraptionMove move, BlockPos source) {
        BlockPos immutable = source.immutable();
        if (!move.sourceFrames().contains(immutable)) {
            throw new IllegalArgumentException(
                    "Cannot release non-source Frame " + source + " from Create move " + move.assemblyId());
        }
        Set<BlockPos> previous = releasedSources(move);
        if (previous.contains(immutable)) {
            return false;
        }
        LinkedHashSet<BlockPos> updated = new LinkedHashSet<>(previous);
        updated.add(immutable);
        ((PendingContraptionMoveReleaseAccess) (Object) move)
                .antikytheramechanism$setReleasedSourceFrames(updated);
        return true;
    }

    /** Source reservation that can be handed to a newly placed physical Frame, if any. */
    public static @Nullable PendingContraptionMove vacatedSourceReservation(
            MechanismAssemblyManager manager,
            BlockPos position) {
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        UUID indexedOwner = access.antikytheramechanism$getFrameIndex().get(position);
        if (indexedOwner == null) {
            return null;
        }
        PendingContraptionMove move =
                access.antikytheramechanism$getPendingContraptionMoves().get(indexedOwner);
        if (move == null
                || move.hasPlacement()
                || !move.sourceFrames().contains(position)
                || isReleased(move, position)) {
            return null;
        }
        return move;
    }

    /** Strict decoder used by the PendingContraptionMove mixin. */
    public static Set<BlockPos> decodeReleasedSources(CompoundTag tag) {
        if (!tag.contains(RELEASED_SOURCE_FRAMES_TAG, Tag.TAG_LONG_ARRAY)) {
            return Set.of();
        }

        long[] releasedPacked = tag.getLongArray(RELEASED_SOURCE_FRAMES_TAG);
        LinkedHashSet<BlockPos> released = new LinkedHashSet<>();
        for (long packed : releasedPacked) {
            if (!released.add(BlockPos.of(packed))) {
                throw new IllegalArgumentException("Duplicate released Create source Frame");
            }
        }

        long[] sourcePacked = tag.getLongArray(SOURCE_FRAMES_TAG);
        LinkedHashSet<BlockPos> sources = new LinkedHashSet<>();
        for (long packed : sourcePacked) {
            if (!sources.add(BlockPos.of(packed))) {
                throw new IllegalArgumentException("Duplicate source Frame in Create journal");
            }
        }
        if (!sources.containsAll(released)) {
            throw new IllegalArgumentException("Released Create source is not part of source_frames");
        }
        return Set.copyOf(released);
    }

    /** Fail-soft decoder used only while the manager is reconstructing frameIndex before journals load. */
    public static @Nullable Set<BlockPos> tryDecodeReleasedSources(CompoundTag tag) {
        try {
            return decodeReleasedSources(tag);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static void setReleasedSources(PendingContraptionMove move, Collection<BlockPos> sources) {
        if (!move.sourceFrames().containsAll(sources)) {
            throw new IllegalArgumentException("Released Create sources must be a subset of sourceFrames");
        }
        ((PendingContraptionMoveReleaseAccess) (Object) move)
                .antikytheramechanism$setReleasedSourceFrames(sources);
    }

    /**
     * Parses only structurally valid release markers from the manager SavedData. Invalid journals are
     * deliberately ignored here so the normal PendingContraptionMove decoder can retain them raw and
     * recovery-lock their assembly instead of weakening fail-closed startup behavior.
     */
    public static Map<UUID, Set<BlockPos>> releasedSourcesByAssembly(CompoundTag managerTag) {
        net.minecraft.nbt.ListTag journals =
                managerTag.getList("pending_contraption_moves", Tag.TAG_COMPOUND);
        java.util.LinkedHashMap<UUID, Set<BlockPos>> result = new java.util.LinkedHashMap<>();
        Set<UUID> ambiguous = new java.util.HashSet<>();
        for (int index = 0; index < journals.size(); index++) {
            CompoundTag journal = journals.getCompound(index);
            if (!journal.hasUUID("assembly_id")) {
                continue;
            }
            UUID id = journal.getUUID("assembly_id");
            Set<BlockPos> released = tryDecodeReleasedSources(journal);
            if (released == null || released.isEmpty() || ambiguous.contains(id)) {
                continue;
            }
            Set<BlockPos> previous = result.putIfAbsent(id, released);
            if (previous != null && !previous.equals(released)) {
                result.remove(id);
                ambiguous.add(id);
            }
        }
        return Map.copyOf(result);
    }
}
