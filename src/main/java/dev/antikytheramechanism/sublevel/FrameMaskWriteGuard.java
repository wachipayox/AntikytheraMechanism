package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Low-level boundary check for writes into Sable plots owned by this mod. */
public final class FrameMaskWriteGuard {
    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<ServiceShellWrite> SERVICE_SHELL_WRITE = new ThreadLocal<>();

    private FrameMaskWriteGuard() {
    }

    public static boolean canWrite(Level level, BlockPos globalPlotPosition, BlockState newState) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }

        BlockState previousState = serverLevel.getBlockState(globalPlotPosition);
        MechanismAssemblyManager parentManager = MechanismAssemblyManager.get(serverLevel);
        if (previousState.is(ModRegistries.MECHANISM_FRAME.get())
                && !newState.is(ModRegistries.MECHANISM_FRAME.get())
                && !parentManager.isPhysicalRelocationTransition(globalPlotPosition)) {
            if (!parentManager.isFrameEvacuated(globalPlotPosition)
                    && !parentManager.evacuateFrame(
                            serverLevel,
                            globalPlotPosition,
                            dev.antikytheramechanism.frame.FrameEvacuationService.Cause.generic())) {
                AntikytheraMechanism.LOGGER.error(
                        "Rejected removal of Mechanism Frame {} because its mini payload could not be evacuated safely",
                        globalPlotPosition);
                return false;
            }
        }
        if (!previousState.is(ModRegistries.MECHANISM_FRAME.get())
                && newState.is(ModRegistries.MECHANISM_FRAME.get())
                && !parentManager.canPlaceFrame(serverLevel, globalPlotPosition)) {
            AntikytheraMechanism.LOGGER.warn(
                    "Rejected Mechanism Frame placement at {} because merge pose or Sable plot bounds are incompatible",
                    globalPlotPosition);
            return false;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, globalPlotPosition);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return true;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return true;
        }

        if (newState.is(ModRegistries.MECHANISM_FRAME.get())) {
            AntikytheraMechanism.LOGGER.warn(
                    "Rejected nested Mechanism Frame write in managed SubLevel {} at {}",
                    subLevel.getUniqueId(),
                    globalPlotPosition);
            return false;
        }

        MechanismAssembly assembly = findManagedAssembly(serverLevel, subLevel);
        if (assembly == null) {
            return newState.isAir();
        }

        BlockPos miniPosition = globalPlotPosition.subtract(subLevel.getPlot().getCenterBlock());
        ServiceShellReservation reservation = ServiceShellReservations.find(serverLevel, ownerId, miniPosition);
        if (reservation != null
                || ServiceShellReservations.isInternalBlock(previousState)
                || ServiceShellReservations.isInternalBlock(newState)) {
            boolean authorized = isAuthorizedServiceShellWrite(
                    serverLevel, assembly, miniPosition, previousState, newState, reservation);
            if (!authorized) {
                AntikytheraMechanism.LOGGER.warn(
                        "Rejected external or mismatched service-shell write for assembly {} at local position {}",
                        assembly.id(),
                        miniPosition);
            }
            return authorized;
        }

        if (newState.is(ModRegistries.ASSEMBLY_ANCHOR.get()) && BYPASS_DEPTH.get() == 0) {
            return false;
        }

        if (BYPASS_DEPTH.get() > 0) {
            return true;
        }
        if (miniPosition.equals(assembly.serviceAnchor())
                && !newState.is(ModRegistries.ASSEMBLY_ANCHOR.get())) {
            return false;
        }

        boolean owned = MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition);
        if (owned
                && DispenserWriteContext.isActive()
                && !newState.isAir()
                && (previousState.isAir() || previousState.canBeReplaced())
                && !MiniaturizableRegistry.isAllowed(newState.getBlock())) {
            AntikytheraMechanism.LOGGER.debug(
                    "Rejected dispenser placement of non-miniaturizable block {} in assembly {} at {}",
                    newState.getBlock(),
                    assembly.id(),
                    miniPosition);
            return false;
        }

        boolean allowed = owned || newState.isAir();
        if (!allowed) {
            AntikytheraMechanism.LOGGER.debug(
                    "Rejected a block write outside FrameMask for assembly {} at local position {}",
                    assembly.id(),
                    miniPosition);
        }
        return allowed;
    }

    private static boolean isAuthorizedServiceShellWrite(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos miniPosition,
            BlockState previousState,
            BlockState newState,
            ServiceShellReservation reservation) {
        ServiceShellWrite write = SERVICE_SHELL_WRITE.get();
        if (write == null
                || write.level() != level
                || reservation == null
                || !reservation.equals(write.reservation())
                || !assembly.id().equals(reservation.assemblyId())
                || !miniPosition.equals(reservation.miniPosition())
                || !ServiceShellReservations.isActive(level, reservation)) {
            return false;
        }
        if (!previousState.isAir()
                && !reservation.expectedBlockId().equals(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(previousState.getBlock()))) {
            return false;
        }
        return newState.isAir()
                || reservation.expectedBlockId().equals(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(newState.getBlock()));
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }

        if (event.getPistonMoveType().isExtend
                && movesManagedBlockOutsideMask(level, event.getPos(), event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }

        for (BlockPos source : resolver.getToPush()) {
            if (movesManagedBlockOutsideMask(level, source, source.relative(resolver.getPushDirection()))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private static boolean movesManagedBlockOutsideMask(ServerLevel level, BlockPos source, BlockPos destination) {
        SubLevel containing = Sable.HELPER.getContaining(level, source);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return false;
        }
        MechanismAssembly assembly = findManagedAssembly(level, subLevel);
        if (assembly == null) {
            return false;
        }
        BlockPos destinationMini = destination.subtract(subLevel.getPlot().getCenterBlock());
        return !MiniCoordinateMapper.isOwnedMiniPosition(assembly, destinationMini);
    }

    private static MechanismAssembly findManagedAssembly(ServerLevel level, ServerSubLevel subLevel) {
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        return ownerId == null ? null : MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
    }

    public static void runBypassing(Runnable action) {
        getBypassing(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T getBypassing(Supplier<T> action) {
        int previousDepth = BYPASS_DEPTH.get();
        BYPASS_DEPTH.set(previousDepth + 1);
        try {
            return action.get();
        } finally {
            if (previousDepth == 0) {
                BYPASS_DEPTH.remove();
            } else {
                BYPASS_DEPTH.set(previousDepth);
            }
        }
    }

    public static <T> T getServiceShellBypassing(
            ServerLevel level,
            ServiceShellReservation reservation,
            Supplier<T> action) {
        if (!ServiceShellReservations.isActive(level, reservation)) {
            throw new IllegalStateException("Service-shell reservation is not active");
        }
        ServiceShellWrite previous = SERVICE_SHELL_WRITE.get();
        SERVICE_SHELL_WRITE.set(new ServiceShellWrite(level, reservation));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SERVICE_SHELL_WRITE.remove();
            } else {
                SERVICE_SHELL_WRITE.set(previous);
            }
        }
    }

    private record ServiceShellWrite(ServerLevel level, ServiceShellReservation reservation) {
    }
}
