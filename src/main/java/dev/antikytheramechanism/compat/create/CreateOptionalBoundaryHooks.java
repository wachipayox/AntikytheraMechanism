package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlock;
import dev.antikytheramechanism.compat.create.transmission.TransmissionLinkCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Optional Create-only boundary work behind a class-loading barrier.
 *
 * <p>The outer methods contain no Create type descriptors. The nested implementation is touched
 * only when Create is actually loaded, so crash recovery can safely call these hooks in worlds
 * started without Create.</p>
 */
final class CreateOptionalBoundaryHooks {
    private CreateOptionalBoundaryHooks() {}

    static void quiesce(ServerLevel level, Collection<UUID> ids) {
        if (ModList.get().isLoaded("create")) Loaded.reconcileBoundaries(level, ids);
    }

    static void rebuild(ServerLevel level, Collection<UUID> ids) {
        if (ModList.get().isLoaded("create")) Loaded.reconcileBoundaries(level, ids);
    }

    private static final class Loaded {
        /**
         * A Create contraption moves the physical Frames, not the real mini blocks in their Sable
         * plot. Reconcile only the macro↔mini transmission boxes here. Clearing every native mini
         * KineticBlockEntity would destroy generator/source/network state even though that internal
         * Create network never moved.
         *
         * <p>During disconnect the movement journal already exists, so reconcile() suspends the
         * boundary and retires its service ports. During reconnect the journal has already been
         * committed/removed, so the same operation rebuilds the valid boundary immediately.</p>
         */
        private static void reconcileBoundaries(ServerLevel level, Collection<UUID> ids) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
            Set<BlockPos> visitedBoxes = new HashSet<>();
            for (UUID id : ids) {
                manager.getAssembly(id).ifPresent(assembly -> {
                    for (BlockPos frame : assembly.frames()) {
                        for (Direction direction : Direction.values()) {
                            BlockPos candidate = frame.relative(direction);
                            if (!level.hasChunkAt(candidate)) {
                                continue;
                            }
                            BlockState state = level.getBlockState(candidate);
                            if (!(state.getBlock() instanceof TransmissionBoxBlock)
                                    || !candidate.relative(state.getValue(TransmissionBoxBlock.FACING)).equals(frame)
                                    || !visitedBoxes.add(candidate)) {
                                continue;
                            }
                            TransmissionLinkCoordinator.reconcile(level, candidate);
                        }
                    }
                });
            }
        }
    }
}
