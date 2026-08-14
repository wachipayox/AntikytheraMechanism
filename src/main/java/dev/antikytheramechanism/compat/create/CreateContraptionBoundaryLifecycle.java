package dev.antikytheramechanism.compat.create;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Collection;
import java.util.UUID;

public final class CreateContraptionBoundaryLifecycle {
    private CreateContraptionBoundaryLifecycle() {}

    public static void disconnect(ServerLevel level, Collection<UUID> ids) {
        CreateOptionalBoundaryHooks.quiesce(level, ids);
        replay(level, ids, false);
    }

    public static void reconnect(ServerLevel level, Collection<UUID> ids) {
        applyFacing(level, ids);
        replay(level, ids, true);
        CreateOptionalBoundaryHooks.rebuild(level, ids);
    }

    private static void applyFacing(ServerLevel level, Collection<UUID> ids) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID id : ids) {
            MechanismAssembly assembly = manager.getAssembly(id).orElse(null);
            if (assembly == null || !assembly.orientation().isUpright()) continue;
            for (BlockPos frame : assembly.frames()) {
                if (!level.hasChunkAt(frame)) continue;
                BlockState state = level.getBlockState(frame);
                if (state.is(ModRegistries.MECHANISM_FRAME.get())
                        && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                        && state.getValue(BlockStateProperties.HORIZONTAL_FACING) != assembly.orientation().front()) {
                    level.setBlock(frame,
                            state.setValue(BlockStateProperties.HORIZONTAL_FACING, assembly.orientation().front()),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            for (BlockPos frame : assembly.frames()) {
                if (level.hasChunkAt(frame)) {
                    level.getBlockState(frame).updateNeighbourShapes(level, frame, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void replay(ServerLevel level, Collection<UUID> ids, boolean connected) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (UUID id : ids) {
            MechanismAssembly assembly = manager.getAssembly(id).orElse(null);
            if (assembly == null) continue;
            ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
            for (BlockPos frame : assembly.frames()) {
                if (!level.hasChunkAt(frame)) continue;
                level.updateNeighborsAt(frame, ModRegistries.MECHANISM_FRAME.get());
                for (Direction physical : Direction.values()) {
                    BlockPos host = frame.relative(physical);
                    if (assembly.containsFrame(host) || !level.hasChunkAt(host)
                            || !MechanismAssemblyHost.samePhysicalHost(level, assembly, host)) continue;
                    level.updateNeighborsAt(host, ModRegistries.MECHANISM_FRAME.get());
                    if (child != null && !child.isRemoved()) replayMiniFace(level, assembly, child, frame, physical, host, connected);
                }
            }
        }
    }

    private static void replayMiniFace(ServerLevel level, MechanismAssembly assembly, ServerSubLevel child,
                                       BlockPos frame, Direction physical, BlockPos host, boolean connected) {
        Direction logical = assembly.orientation().toLogical(physical);
        BlockState external = connected
                ? level.getBlockState(host)
                : MechanismAssemblyManager.get(level)
                        .pendingContraptionBoundaryState(assembly.id(), host)
                        .orElse(Blocks.AIR.defaultBlockState());
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int x = a, y = b, z = 0;
            switch (logical.getAxis()) {
                case X -> { x = logical == Direction.WEST ? 0 : 1; y = a; z = b; }
                case Y -> { x = a; y = logical == Direction.DOWN ? 0 : 1; z = b; }
                case Z -> { x = a; y = b; z = logical == Direction.NORTH ? 0 : 1; }
            }
            BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
            BlockPos mini = MechanismSubLevelService.toPlotPosition(child, local);
            if (!level.hasChunkAt(mini)) continue;
            BlockState before = level.getBlockState(mini);
            if (before.isAir()) continue;
            BlockPos shell = mini.relative(logical);
            Runnable action = () -> {
                BlockState updated = before.updateShape(logical, external, level, mini, shell);
                if (!updated.equals(before)) {
                    Block.updateOrDestroy(before, updated, level, mini, Block.UPDATE_ALL);
                }

                // updateShape is intentionally conservative for several vanilla/modded attachable
                // blocks. A Create boundary can disappear while the Frame is assembled (for example
                // the carried floor is broken), so on reconnect we must also re-run the definitive
                // survival predicate against the now-current macro boundary. Otherwise the stale
                // snapshot can leave dust, torches, rails, etc. floating forever after disassembly.
                BlockState state = level.getBlockState(mini);
                if (!state.isAir() && !state.canSurvive(level, mini)) {
                    Block.updateOrDestroy(state, Blocks.AIR.defaultBlockState(), level, mini, Block.UPDATE_ALL);
                    state = level.getBlockState(mini);
                }

                if (!state.isAir()) {
                    state.handleNeighborChanged(level, mini, external.getBlock(), shell, false);
                    level.updateNeighborsAt(mini, state.getBlock());
                }
            };
            MiniWorldEnvironment.withVirtualReads(action);
        }
    }
}
