package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The authoritative set of full-size frame cells that are backed by mini space.
 */
public final class FrameMask {
    private final BlockPos origin;
    private final Set<BlockPos> frames = new HashSet<>();

    public FrameMask(BlockPos origin, Collection<BlockPos> frames) {
        this.origin = origin.immutable();
        frames.forEach(pos -> this.frames.add(pos.immutable()));
    }

    public Set<BlockPos> frames() {
        return Collections.unmodifiableSet(frames);
    }

    public boolean containsFrame(BlockPos framePos) {
        return frames.contains(framePos);
    }

    public boolean containsMini(BlockPos miniPos) {
        BlockPos frameOffset = new BlockPos(
                Math.floorDiv(miniPos.getX(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getY(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS),
                Math.floorDiv(miniPos.getZ(), MiniCoordinateMapper.CELLS_PER_FRAME_AXIS));
        return frames.contains(origin.offset(frameOffset));
    }

    public void addFrame(BlockPos framePos) {
        frames.add(framePos.immutable());
    }

    public void addFrames(Collection<BlockPos> framePositions) {
        framePositions.forEach(this::addFrame);
    }

    public void removeFrame(BlockPos framePos) {
        frames.remove(framePos);
    }

    public void removeFrames(Collection<BlockPos> framePositions) {
        framePositions.forEach(frames::remove);
    }
}
