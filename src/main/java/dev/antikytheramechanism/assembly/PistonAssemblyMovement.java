package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.neoforge.event.level.PistonEvent;

/** High-level all-or-nothing preflight for parent-world Mechanism Frames. */
public final class PistonAssemblyMovement {
    private PistonAssemblyMovement() {
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null) {
            return;
        }
        if (!resolver.resolve()) {
            prepareStickyRetractionBehindHead(event, level);
            return;
        }
        AntikytheraMechanism.LOGGER.debug(
                "Piston Pre at {} type={} pushDirection={} toPush={} toDestroy={}",
                event.getPos(),
                event.getPistonMoveType(),
                resolver.getPushDirection(),
                resolver.getToPush(),
                resolver.getToDestroy());

        // This must remain impossible even if another mod changes a frame's push
        // reaction. Destruction has no carrier from which to recover the BE pointer.
        if (resolver.getToDestroy().stream()
                .anyMatch(position -> level.getBlockState(position).is(ModRegistries.MECHANISM_FRAME.get()))) {
            event.setCanceled(true);
            return;
        }

        boolean safe = MechanismAssemblyManager.get(level).preparePistonMoves(
                level,
                event.getPos(),
                resolver.getPushDirection(),
                event.getPistonMoveType().isExtend,
                resolver.getToPush());
        if (!safe) {
            event.setCanceled(true);
        }
    }

    /**
     * NeoForge's retraction helper runs before vanilla removes/finalizes the old
     * piston head at {@code piston + facing}. Consequently it reports false for a
     * pullable block at {@code piston + 2*facing}; the private resolver created by
     * {@code moveBlocks(false)} runs after head removal and succeeds. Journal the
     * complete managed frame assembly explicitly across that one-block discrepancy.
     */
    private static void prepareStickyRetractionBehindHead(
            PistonEvent.Pre event,
            ServerLevel level) {
        if (event.getPistonMoveType() != PistonEvent.PistonMoveType.RETRACT
                || !level.getBlockState(event.getPos()).is(Blocks.STICKY_PISTON)) {
            return;
        }

        BlockPos pulledPosition = event.getPos().relative(event.getDirection(), 2);
        if (!level.getBlockState(pulledPosition).is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(pulledPosition).orElse(null);
        boolean safe = assembly != null && manager.preparePistonMoves(
                level,
                event.getPos(),
                event.getDirection().getOpposite(),
                false,
                assembly.frames());
        AntikytheraMechanism.LOGGER.debug(
                "Sticky retraction fallback at {} for frame {} assembly={} safe={}",
                event.getPos(),
                pulledPosition,
                assembly == null ? null : assembly.id(),
                safe);
        if (!safe) {
            event.setCanceled(true);
        }
    }
}
