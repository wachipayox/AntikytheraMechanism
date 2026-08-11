package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.create.KineticPortType;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.ServiceShellReservation;
import dev.antikytheramechanism.sublevel.ServiceShellReservations;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative installer and recovery loop for Create transmission endpoints. */
public final class TransmissionLinkCoordinator {
    private TransmissionLinkCoordinator() {
    }

    public static void reconcile(ServerLevel level, BlockPos boxPosition) {
        if (!level.hasChunkAt(boxPosition)
                || !(level.getBlockEntity(boxPosition) instanceof TransmissionBoxBlockEntity box)
                || !(level.getBlockState(boxPosition).getBlock() instanceof TransmissionBoxBlock boxBlock)) {
            return;
        }

        BlockState boxState = level.getBlockState(boxPosition);
        BlockPos framePosition = boxPosition.relative(boxState.getValue(TransmissionBoxBlock.FACING));
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);

        if (assembly == null || manager.isContentRecoveryLocked(assembly.id())) {
            MechanismAssembly previous = box.assemblyId() == null
                    ? null
                    : manager.getAssembly(box.assemblyId()).orElse(null);
            quiesce(level, box);
            if (previous != null) {
                retireOwnedPorts(level, previous, boxPosition, box.linkNonce(), Set.of());
            }
            box.suspend(assembly == null ? TransmissionLinkState.UNBOUND : TransmissionLinkState.SUSPENDED);
            return;
        }

        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null || subLevel.isRemoved()) {
            quiesce(level, box);
            box.suspend(TransmissionLinkState.SUSPENDED);
            return;
        }

        UUID previousAssemblyId = box.assemblyId();
        UUID previousNonce = box.linkNonce();
        quiesce(level, box);
        if (previousAssemblyId != null && !previousAssemblyId.equals(assembly.id())) {
            manager.getAssembly(previousAssemblyId).ifPresent(previous ->
                    retireOwnedPorts(level, previous, boxPosition, previousNonce, Set.of()));
        }
        box.bind(assembly.id());

        TransmissionFaceOrientation orientation = TransmissionBoxBlock.orientation(boxState);
        BlockPos frameMiniBase = MiniCoordinateMapper.frameToMini(assembly, framePosition, 0, 0, 0);
        List<TransmissionPortLayout.PortPlacement> placements = TransmissionPortLayout.create(
                boxBlock.kind(),
                orientation,
                boxState.getValue(TransmissionBoxBlock.DIAGONAL_B),
                box.coverMask(),
                frameMiniBase);

        List<PreparedPort> prepared = new ArrayList<>();
        for (TransmissionPortLayout.PortPlacement placement : placements) {
            PreparedPort port = preparePort(level, assembly, subLevel, boxPosition, box, placement);
            if (port != null) {
                prepared.add(port);
            }
        }
        // Ports are independent. An empty/mismatched quadrant is simply disconnected; requiring
        // all four targets would make one shaft/cog impossible to use and could create artificial
        // cog cycles between otherwise unrelated mini machines.
        if (prepared.isEmpty()) {
            retireOwnedPorts(level, assembly, boxPosition, box.linkNonce(), Set.of());
            box.suspend(TransmissionLinkState.SUSPENDED);
            return;
        }

        List<ServiceShellReservation> requested = prepared.stream().map(PreparedPort::reservation).toList();
        ServiceShellReservations.BatchResult reservationResult =
                ServiceShellReservations.reserveBatch(level, assembly, requested);
        if (!reservationResult.succeeded()) {
            retireOwnedPorts(level, assembly, boxPosition, box.linkNonce(), Set.of());
            box.suspend(reservationResult.status() == ServiceShellReservations.BatchStatus.CONFLICT
                    ? TransmissionLinkState.CONFLICT
                    : TransmissionLinkState.SUSPENDED);
            return;
        }

        Set<BlockPos> desiredLocalPositions = new HashSet<>();
        for (PreparedPort port : prepared) {
            desiredLocalPositions.add(port.reservation().miniPosition());
        }
        retireOwnedPorts(level, assembly, boxPosition, box.linkNonce(), desiredLocalPositions);

        for (PreparedPort port : prepared) {
            ServiceShellReservations.OperationResult installed = ServiceShellReservations.install(
                    level, assembly, port.reservation(), port.proxyState());
            if (installed != ServiceShellReservations.OperationResult.SUCCESS) {
                box.suspend(installed == ServiceShellReservations.OperationResult.CONFLICT
                        ? TransmissionLinkState.CONFLICT
                        : TransmissionLinkState.RECOVERY_REQUIRED);
                return;
            }
            ServiceShellReservations.OperationResult configured = ServiceShellReservations.configure(
                    level,
                    assembly,
                    port.reservation(),
                    blockEntity -> {
                        if (!(blockEntity instanceof InternalTransmissionPortBlockEntity proxy)) {
                            throw new IllegalStateException("Reserved transmission endpoint has an unexpected BlockEntity");
                        }
                        proxy.configure(box.linkNonce(), assembly.id(), boxPosition, port.placement().portIndex());
                    });
            if (configured != ServiceShellReservations.OperationResult.SUCCESS) {
                box.suspend(configured == ServiceShellReservations.OperationResult.CONFLICT
                        ? TransmissionLinkState.CONFLICT
                        : TransmissionLinkState.RECOVERY_REQUIRED);
                return;
            }
        }

        Map<Integer, BlockPos> peers = new HashMap<>();
        for (PreparedPort port : prepared) {
            peers.put(port.placement().portIndex(), port.globalServicePosition());
        }
        box.activate(assembly.id(), peers);
        for (PreparedPort port : prepared) {
            BlockEntity blockEntity = level.getBlockEntity(port.globalServicePosition());
            if (!(blockEntity instanceof InternalTransmissionPortBlockEntity proxy)
                    || !proxy.matches(
                            box.linkNonce(), assembly.id(), boxPosition, port.placement().portIndex())) {
                quiesce(level, box);
                box.suspend(TransmissionLinkState.RECOVERY_REQUIRED);
                return;
            }
            proxy.setRemoteEnabled(true);
        }

        // All endpoints share the parent ServerLevel with the Sable plot. These remote +1 edges
        // therefore form one native Create KineticNetwork, including its stress/conflict rules.
        for (PreparedPort port : prepared) {
            if (level.getBlockEntity(port.globalServicePosition()) instanceof InternalTransmissionPortBlockEntity proxy) {
                proxy.attachKinetics();
            }
        }
        if (level.getBlockEntity(boxPosition) == box) {
            box.attachKinetics();
        }
    }

    public static void remove(ServerLevel level, BlockPos boxPosition, TransmissionBoxBlockEntity box) {
        MechanismAssembly assembly = box.assemblyId() == null
                ? null
                : MechanismAssemblyManager.get(level).getAssembly(box.assemblyId()).orElse(null);
        quiesce(level, box);
        if (assembly != null) {
            retireOwnedPorts(level, assembly, boxPosition, box.linkNonce(), Set.of());
        }
        box.suspend(TransmissionLinkState.UNBOUND);
    }

    /** Used by transactional assembly lifecycle hooks before mini KBE snapshots are taken. */
    public static void quiesceAssembly(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            return;
        }
        Set<BlockPos> visitedBoxes = new HashSet<>();
        for (BlockPos frame : assembly.frames()) {
            for (Direction direction : Direction.values()) {
                BlockPos candidate = frame.relative(direction);
                if (!visitedBoxes.add(candidate) || !level.hasChunkAt(candidate)) {
                    continue;
                }
                BlockState candidateState = level.getBlockState(candidate);
                if (candidateState.getBlock() instanceof TransmissionBoxBlock
                        && candidate.relative(candidateState.getValue(TransmissionBoxBlock.FACING)).equals(frame)
                        && level.getBlockEntity(candidate) instanceof TransmissionBoxBlockEntity box) {
                    quiesce(level, box);
                    box.suspend(TransmissionLinkState.SUSPENDED);
                }
            }
            BlockPos base = MiniCoordinateMapper.frameToMini(assembly, frame, 0, 0, 0);
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 2; z++) {
                        BlockPos global = MechanismSubLevelService.toPlotPosition(
                                subLevel, base.offset(x, y, z));
                        if (level.hasChunkAt(global)
                                && level.getBlockEntity(global) instanceof KineticBlockEntity kinetic) {
                            quiesceKinetic(kinetic);
                        }
                    }
                }
            }
        }
    }

    /** Rebuilds only loaded native Create nodes; transmission boxes revalidate on their next tick. */
    public static void rebuildAssemblyKinetics(ServerLevel level, MechanismAssembly assembly) {
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            return;
        }
        for (BlockPos frame : assembly.frames()) {
            BlockPos base = MiniCoordinateMapper.frameToMini(assembly, frame, 0, 0, 0);
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 2; z++) {
                        BlockPos global = MechanismSubLevelService.toPlotPosition(
                                subLevel, base.offset(x, y, z));
                        if (level.hasChunkAt(global)
                                && level.getBlockEntity(global) instanceof KineticBlockEntity kinetic) {
                            kinetic.attachKinetics();
                        }
                    }
                }
            }
        }
    }

    private static PreparedPort preparePort(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel subLevel,
            BlockPos boxPosition,
            TransmissionBoxBlockEntity box,
            TransmissionPortLayout.PortPlacement placement) {
        if (!MechanismSubLevelService.canAddressMiniPosition(
                level, subLevel, placement.serviceLocalPosition())
                || !MechanismSubLevelService.canAddressMiniPosition(
                        level, subLevel, placement.targetLocalPosition())) {
            return null;
        }
        BlockPos globalService = MechanismSubLevelService.toPlotPosition(
                subLevel, placement.serviceLocalPosition());
        BlockPos globalTarget = MechanismSubLevelService.toPlotPosition(
                subLevel, placement.targetLocalPosition());
        if (!level.hasChunkAt(globalService) || !level.hasChunkAt(globalTarget)) {
            return null;
        }
        BlockState targetState = level.getBlockState(globalTarget);
        if (!(level.getBlockEntity(globalTarget) instanceof KineticBlockEntity)
                || !validTarget(level, globalTarget, targetState, placement)) {
            return null;
        }

        ResourceLocation proxyId = proxyId(placement.servicePortType());
        ServiceShellReservation reservation = new ServiceShellReservation(
                assembly.id(),
                placement.serviceLocalPosition(),
                box.linkNonce(),
                placement.portIndex(),
                proxyId);
        return new PreparedPort(
                placement,
                reservation,
                proxyState(placement),
                globalService.immutable());
    }

    private static boolean validTarget(
            ServerLevel level,
            BlockPos targetPosition,
            BlockState state,
            TransmissionPortLayout.PortPlacement placement) {
        Block block = state.getBlock();
        if (!(block instanceof IRotate rotate)
                || rotate.getRotationAxis(state) != placement.rotationAxis()) {
            return false;
        }
        return switch (placement.targetPortType()) {
            case SHAFT -> rotate.hasShaftTowards(
                    level,
                    targetPosition,
                    state,
                    placement.shaftFacing().getOpposite());
            case SMALL_COG -> ICogWheel.isSmallCog(state);
            case LARGE_COG -> ICogWheel.isLargeCog(state);
        };
    }

    private static ResourceLocation proxyId(KineticPortType type) {
        return switch (type) {
            case SHAFT -> AntikytheraMechanism.id("internal_shaft_port");
            case SMALL_COG -> AntikytheraMechanism.id("internal_small_cog_port");
            case LARGE_COG -> AntikytheraMechanism.id("internal_large_cog_port");
        };
    }

    private static BlockState proxyState(TransmissionPortLayout.PortPlacement placement) {
        return switch (placement.servicePortType()) {
            case SHAFT -> CreateTransmissionRegistries.INTERNAL_SHAFT_PORT.get()
                    .defaultBlockState()
                    .setValue(InternalShaftPortBlock.FACING, placement.shaftFacing());
            case SMALL_COG -> CreateTransmissionRegistries.INTERNAL_SMALL_COG_PORT.get()
                    .defaultBlockState()
                    .setValue(InternalCogPortBlock.AXIS, placement.rotationAxis());
            case LARGE_COG -> CreateTransmissionRegistries.INTERNAL_LARGE_COG_PORT.get()
                    .defaultBlockState()
                    .setValue(InternalCogPortBlock.AXIS, placement.rotationAxis());
        };
    }

    private static void quiesce(ServerLevel level, TransmissionBoxBlockEntity box) {
        if (box.isActive()) {
            box.detachKinetics();
        }
        for (BlockPos peer : box.activePeers().values()) {
            if (level.hasChunkAt(peer)
                    && level.getBlockEntity(peer) instanceof InternalTransmissionPortBlockEntity proxy) {
                if (proxy.isRemoteEnabled()) {
                    proxy.detachKinetics();
                }
                proxy.setRemoteEnabled(false);
                quiesceKinetic(proxy);
            }
        }
        quiesceKinetic(box);
    }

    private static void quiesceKinetic(KineticBlockEntity kinetic) {
        if (kinetic.hasSource() || kinetic.isSource() || kinetic.hasNetwork()) {
            kinetic.detachKinetics();
            kinetic.removeSource();
        }
        kinetic.clearKineticInformation();
        kinetic.setChanged();
    }

    private static void retireOwnedPorts(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos boxPosition,
            UUID ownerNonce,
            Set<BlockPos> retainedLocalPositions) {
        ServerSubLevel subLevel = MechanismSubLevelService.findExisting(level, assembly);
        if (subLevel == null) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos frame : assembly.frames()) {
            BlockPos base = MiniCoordinateMapper.frameToMini(assembly, frame, 0, 0, 0);
            for (Direction miniFace : Direction.values()) {
                TransmissionFaceOrientation shellFace = new TransmissionFaceOrientation(miniFace, 0);
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    BlockPos local = TransmissionPortLayout.placement(
                                    TransmissionBoxKind.FOUR_SHAFTS, shellFace, base, quadrant)
                            .serviceLocalPosition();
                    if (!visited.add(local) || retainedLocalPositions.contains(local)) {
                        continue;
                    }
                    BlockPos global = MechanismSubLevelService.toPlotPosition(subLevel, local);
                    if (!level.hasChunkAt(global)
                            || !(level.getBlockEntity(global) instanceof InternalTransmissionPortBlockEntity proxy)
                            || proxy.ownerNonce() == null
                            || !proxy.ownerNonce().equals(ownerNonce)
                            || !assembly.id().equals(proxy.assemblyId())
                            || !boxPosition.equals(proxy.parentBoxPos())) {
                        continue;
                    }
                    BlockState actual = level.getBlockState(global);
                    if (!ServiceShellReservations.isInternalBlock(actual)) {
                        continue;
                    }
                    ServiceShellReservation reservation = new ServiceShellReservation(
                            assembly.id(),
                            local,
                            ownerNonce,
                            proxy.portIndex(),
                            BuiltInRegistries.BLOCK.getKey(actual.getBlock()));
                    if (!ServiceShellReservations.reserveBatch(level, assembly, List.of(reservation)).succeeded()) {
                        AntikytheraMechanism.LOGGER.error(
                                "Could not recover reservation for stale transmission endpoint {} of box {}",
                                local,
                                boxPosition);
                        continue;
                    }
                    ServiceShellReservations.retire(
                            level,
                            assembly,
                            reservation,
                            () -> quiesceKinetic(proxy));
                }
            }
        }
    }

    private record PreparedPort(
            TransmissionPortLayout.PortPlacement placement,
            ServiceShellReservation reservation,
            BlockState proxyState,
            BlockPos globalServicePosition) {
    }
}
