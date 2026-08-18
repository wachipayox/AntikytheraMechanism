package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Recovers successful non-air writes that land outside a managed FrameMask.
 *
 * <p>The low-level write is deliberately allowed to complete first. That matters for placement
 * engines which consume material before calling {@code Level#setBlock}, and for BlockEntities whose
 * NBT/storage is populated immediately after that call returns. The accepted plot cell is therefore
 * a short-lived overflow journal entry, never legitimate managed content. At the end of the level
 * tick, after the originating placement stack has unwound, the final block/BlockEntity loot is
 * captured, projected through the SubLevel pose into physical world space, and the plot cell is
 * force-cleared with drops suppressed.</p>
 */
public final class FrameMaskOverflowDropService {
    private static final int CLEAR_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final Map<ServerLevel, Map<BlockPos, PendingOverflow>> PENDING = new WeakHashMap<>();

    private FrameMaskOverflowDropService() {
    }

    public static void recordSuccessfulOverflow(
            ServerLevel level,
            ServerSubLevel subLevel,
            MechanismAssembly assembly,
            BlockPos globalPlotPosition,
            BlockState acceptedState) {
        PendingOverflow overflow = new PendingOverflow(
                subLevel,
                assembly.id(),
                globalPlotPosition.immutable(),
                acceptedState);
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                    .put(globalPlotPosition.immutable(), overflow);
        }
    }

    /** Runs from LevelTick.Post, after ordinary block-placement call stacks have completed. */
    public static void tick(ServerLevel level) {
        Map<BlockPos, PendingOverflow> pending;
        synchronized (PENDING) {
            pending = PENDING.remove(level);
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        for (PendingOverflow overflow : pending.values()) {
            recover(level, overflow);
        }
    }

    private static void recover(ServerLevel level, PendingOverflow overflow) {
        ServerSubLevel subLevel = overflow.subLevel();
        BlockPos global = overflow.globalPlotPosition();
        if (subLevel.isRemoved()) {
            AntikytheraMechanism.LOGGER.error(
                    "Could not recover FrameMask overflow at {} for assembly {} because its SubLevel was removed before LevelTick.Post",
                    global,
                    overflow.assemblyId());
            return;
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssembly(overflow.assemblyId())
                .orElse(null);
        if (assembly != null) {
            BlockPos mini = global.subtract(subLevel.getPlot().getCenterBlock());
            if (MiniCoordinateMapper.isOwnedMiniPosition(assembly, mini)) {
                // The graph expanded before the deferred recovery ran. Do not destroy a cell which
                // has become legitimate managed content in the meantime.
                AntikytheraMechanism.LOGGER.debug(
                        "Canceled FrameMask overflow recovery at {} because assembly {} now owns the mini cell {}",
                        global,
                        assembly.id(),
                        mini);
                return;
            }
        }

        BlockState state = level.getBlockState(global);
        if (state.isAir()) {
            return;
        }
        if (state.getBlock() != overflow.acceptedState().getBlock()) {
            // A later write resolved/replaced the transient cell. Never turn that newer state into a
            // drop for an older placement journal entry.
            AntikytheraMechanism.LOGGER.debug(
                    "Canceled stale FrameMask overflow recovery at {}: accepted {}, now {}",
                    global,
                    overflow.acceptedState().getBlock(),
                    state.getBlock());
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(global);
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(
                state,
                level,
                global,
                blockEntity,
                null,
                ItemStack.EMPTY));

        // Match Frame evacuation semantics for mounted/container payloads: the structural block loot
        // and the inventory it currently owns are both material which must survive the overflow.
        if (blockEntity instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack contained = container.getItem(slot);
                if (!contained.isEmpty()) {
                    drops.add(contained.copy());
                }
            }
        }

        Vec3 physicalPosition = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(global));
        boolean cleared = FrameMaskWriteGuard.getBypassing(() -> {
            Clearable.tryClear(level.getBlockEntity(global));
            level.setBlock(global, Blocks.AIR.defaultBlockState(), CLEAR_FLAGS);
            if (!level.getBlockState(global).isAir()) {
                // The overflow contract is stronger than an ordinary failed replacement: make one
                // final no-drop removal attempt before declaring the invariant broken.
                level.removeBlock(global, false);
            }
            return level.getBlockState(global).isAir();
        });

        if (!cleared) {
            AntikytheraMechanism.LOGGER.error(
                    "CRITICAL: could not force-clear FrameMask overflow {} ({}) for assembly {}",
                    global,
                    state.getBlock(),
                    overflow.assemblyId());
        }

        // The carried material has already been consumed by the successful placement. Commit its
        // physical drops even if a pathological mod prevents the cleanup write; otherwise the exact
        // disappearance this service exists to prevent would recur.
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemEntity item = new ItemEntity(
                    level,
                    physicalPosition.x,
                    physicalPosition.y,
                    physicalPosition.z,
                    stack.copy());
            if (!level.addFreshEntity(item)) {
                AntikytheraMechanism.LOGGER.error(
                        "Could not spawn FrameMask overflow drop {} at physical position {}",
                        stack,
                        physicalPosition);
            }
        }
    }

    private record PendingOverflow(
            ServerSubLevel subLevel,
            UUID assemblyId,
            BlockPos globalPlotPosition,
            BlockState acceptedState) {
    }
}
