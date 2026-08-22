package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.FramePlacementFeedbackHooks;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.AssemblyPoseDriver;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;
import java.util.function.Supplier;

public final class MiniPlacementRouter {
    private static final double HIT_EPSILON = 1.0E-6;
    private static final double FACE_PROBE_EPSILON = 1.0E-4;
    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MiniPlacementRouter() {}
    public static boolean isBypassing() { return BYPASS_DEPTH.get() > 0; }

    public static @Nullable InteractionResult route(BlockItem blockItem, UseOnContext context) {
        if (BYPASS_DEPTH.get() > 0) return null;
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        if (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()) return null;

        BlockPos framePos;
        CellSelection selection;
        boolean parentSupport;
        if (clickedState.is(ModRegistries.MECHANISM_FRAME.get())) {
            if (!isInteriorFacingFrameHit(clickedPos, context.getClickedFace(), context.getClickLocation())) return null;
            framePos = clickedPos;
            selection = selectDirectCell(framePos, context.getClickLocation());
            parentSupport = false;
        } else {
            BlockPlaceContext vanillaContext = new BlockPlaceContext(context);
            BlockPos vanillaTarget = vanillaContext.getClickedPos();
            if (vanillaTarget.equals(clickedPos)
                    || !level.getBlockState(vanillaTarget).is(ModRegistries.MECHANISM_FRAME.get())
                    || !MechanismAssemblyHost.samePhysicalHost(level, clickedPos, vanillaTarget)) return null;
            framePos = vanillaTarget;
            selection = selectBoundaryCell(framePos, context.getClickedFace(), context.getClickLocation());
            parentSupport = true;
        }

        if (!MiniaturizableRegistry.isAllowed(blockItem.getBlock())) {
            FramePlacementFeedbackHooks.rejectedPlacement(level, framePos);
            return InteractionResult.FAIL;
        }
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return place((ServerLevel) level, framePos, context.getClickedFace(), selection,
                parentSupport, player, context.getHand(), context.getItemInHand());
    }

    /**
     * Redirects an ordinary BlockItem use from one managed Frame child into an adjacent Frame owned
     * by a different assembly. The destination placement still executes vanilla BlockItem semantics;
     * only its coordinate system and read-only support block are rebased into the destination child.
     */
    public static InteractionResult placeInNeighborFrame(
            BlockItem blockItem,
            UseOnContext context,
            ManagedMiniPlacementTargets.NeighborFrameTarget neighborTarget) {
        if (!(context.getLevel() instanceof ServerLevel level) || BYPASS_DEPTH.get() > 0) {
            return InteractionResult.FAIL;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }

        BlockPos sourceGlobal = context.getClickedPos();
        SubLevel containing = Sable.HELPER.getContaining(level, sourceGlobal);
        if (!(containing instanceof ServerSubLevel sourceSubLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(sourceSubLevel)) {
            return InteractionResult.FAIL;
        }
        UUID sourceOwnerId = MechanismSubLevelService.getOwnerAssemblyId(sourceSubLevel);
        if (sourceOwnerId == null) {
            return InteractionResult.FAIL;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssembly(sourceOwnerId).orElse(null);
        MechanismAssembly destinationAssembly = manager.getAssembly(
                neighborTarget.destinationAssemblyId()).orElse(null);
        if (sourceAssembly == null
                || destinationAssembly == null
                || sourceAssembly.id().equals(destinationAssembly.id())
                || !sourceAssembly.containsFrame(neighborTarget.sourceFrame())
                || !destinationAssembly.containsFrame(neighborTarget.destinationFrame())) {
            return InteractionResult.FAIL;
        }

        Direction sourceLogicalFace = context.getClickedFace();
        Direction physicalFace = sourceAssembly.orientation().toPhysical(sourceLogicalFace);
        if (physicalFace == null
                || !neighborTarget.destinationFrame().equals(
                        neighborTarget.sourceFrame().relative(physicalFace))) {
            // Ordinary BlockItem placement crosses exactly one face. Diagonal NeighborFrameTargets
            // are valid for placement helpers but cannot be reached by one vanilla useOn step.
            return InteractionResult.FAIL;
        }
        Direction destinationLogicalFace = destinationAssembly.orientation().toLogical(physicalFace);
        if (destinationLogicalFace == null) {
            return InteractionResult.FAIL;
        }

        ServerSubLevel destinationSubLevel = MechanismSubLevelService.ensureForContent(
                level, destinationAssembly);
        if (destinationSubLevel == null) {
            return InteractionResult.FAIL;
        }
        LazySubLevelLifecycle.requestRetirementCheck(level, destinationAssembly.id());

        BlockPos destinationGlobal = neighborTarget.destinationGlobalPosition();
        BlockPos destinationMini = destinationGlobal.subtract(destinationSubLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(destinationAssembly, destinationMini)
                || !MiniCoordinateMapper.miniToFrame(destinationAssembly, destinationMini)
                        .equals(neighborTarget.destinationFrame())
                || !MechanismSubLevelService.canAddressMiniPosition(
                        level, destinationSubLevel, destinationMini)) {
            return InteractionResult.FAIL;
        }

        BlockState before = level.getChunkAt(destinationGlobal).getBlockState(destinationGlobal);
        if (!before.canBeReplaced()) {
            return InteractionResult.FAIL;
        }

        BlockState sourceSupport = level.getChunkAt(sourceGlobal).getBlockState(sourceGlobal);
        if (sourceSupport.isAir()) {
            return InteractionResult.FAIL;
        }
        BlockState destinationAxesSupport = sourceSupport.rotate(neighborTarget.stateRotation());
        BlockPos syntheticSupport = destinationGlobal.relative(destinationLogicalFace.getOpposite());
        Vec3 destinationWithinCell = mapCrossFrameHitWithinCell(
                sourceAssembly,
                destinationAssembly,
                sourceSubLevel,
                destinationSubLevel,
                sourceGlobal,
                destinationGlobal,
                physicalFace,
                context.getClickLocation());
        BlockHitResult destinationHit = new BlockHitResult(
                syntheticHitLocation(
                        syntheticSupport,
                        destinationLogicalFace,
                        new CellSelection(
                                0, 0, 0,
                                destinationWithinCell.x,
                                destinationWithinCell.y,
                                destinationWithinCell.z)),
                destinationLogicalFace,
                syntheticSupport,
                false);

        ItemStack stack = context.getItemInHand();
        InteractionResult placementResult = MiniWorldEnvironment.withCrossFrameVirtualSupport(
                level,
                syntheticSupport,
                destinationAxesSupport,
                () -> withBypass(() -> stack.useOn(
                        new UseOnContext(player, context.getHand(), destinationHit))));

        BlockState after = level.getChunkAt(destinationGlobal).getBlockState(destinationGlobal);
        boolean placed = placementResult.consumesAction()
                && !after.isAir()
                && !after.equals(before);
        if (placed) {
            manager.refreshFrame(level, neighborTarget.destinationFrame());
        }
        return placed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static InteractionResult place(
            ServerLevel level,
            BlockPos framePos,
            Direction physicalClickedFace,
            CellSelection physicalSelection,
            boolean parentSupport,
            Player player,
            InteractionHand hand,
            ItemStack stack) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        if (manager.isFrameLifecycleLocked(framePos) || !MechanismAssemblyHost.canHostFrame(level, framePos)) return InteractionResult.FAIL;
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElse(null);
        if (assembly == null) assembly = manager.onFramePlaced(level, framePos);

        ServerSubLevel subLevel = MechanismSubLevelService.ensureForContent(level, assembly);
        if (subLevel == null) return InteractionResult.FAIL;
        LazySubLevelLifecycle.requestRetirementCheck(level, assembly.id());
        AssemblyPose worldTarget = MechanismAssemblyHost.worldPose(level, assembly);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (worldTarget == null || container == null) return InteractionResult.FAIL;
        AssemblyPoseDriver.drive(container.physicsSystem().getPipeline(), subLevel, worldTarget);

        Direction logicalClickedFace = assembly.orientation().toLogical(physicalClickedFace);
        CellSelection logicalSelection = toLogicalSelection(assembly, physicalSelection);
        BlockPos miniPosition = MiniCoordinateMapper.frameToMini(
                assembly, framePos, logicalSelection.x(), logicalSelection.y(), logicalSelection.z());
        if (!MechanismSubLevelService.canAddressMiniPosition(level, subLevel, miniPosition)) return InteractionResult.FAIL;

        BlockPos globalTarget = MechanismSubLevelService.toPlotPosition(subLevel, miniPosition);
        BlockState before = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        if (!before.canBeReplaced()) return InteractionResult.FAIL;

        BlockPos syntheticClickedPos = parentSupport
                ? virtualSupportPosition(globalTarget, logicalClickedFace)
                : globalTarget;
        Vec3 localHitLocation = syntheticHitLocation(syntheticClickedPos, logicalClickedFace, logicalSelection);
        BlockHitResult localHit = new BlockHitResult(localHitLocation, logicalClickedFace, syntheticClickedPos, false);

        InteractionResult placementResult;
        if (parentSupport) {
            placementResult = MiniWorldEnvironment.withVirtualReads(
                    () -> withBypass(() -> stack.useOn(new UseOnContext(player, hand, localHit))));
        } else {
            placementResult = withBypass(() -> stack.useOn(new UseOnContext(player, hand, localHit)));
        }

        BlockState after = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        boolean placed = placementResult.consumesAction() && !after.isAir() && !after.equals(before);
        if (placed) manager.refreshFrame(level, framePos);
        return placed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static Vec3 mapCrossFrameHitWithinCell(
            MechanismAssembly sourceAssembly,
            MechanismAssembly destinationAssembly,
            ServerSubLevel sourceSubLevel,
            ServerSubLevel destinationSubLevel,
            BlockPos sourceGlobal,
            BlockPos destinationGlobal,
            Direction physicalFace,
            Vec3 sourceHitLocation) {
        BlockPos sourceMini = sourceGlobal.subtract(sourceSubLevel.getPlot().getCenterBlock());
        BlockPos sourceCell = MiniCoordinateMapper.cellInFrame(sourceMini);
        double sourceWithinX = clampWithinCell(sourceHitLocation.x - sourceGlobal.getX());
        double sourceWithinY = clampWithinCell(sourceHitLocation.y - sourceGlobal.getY());
        double sourceWithinZ = clampWithinCell(sourceHitLocation.z - sourceGlobal.getZ());

        double sourceLogicalX = (sourceCell.getX() + sourceWithinX) * 0.5;
        double sourceLogicalY = (sourceCell.getY() + sourceWithinY) * 0.5;
        double sourceLogicalZ = (sourceCell.getZ() + sourceWithinZ) * 0.5;
        Vector3d sourcePhysical = sourceAssembly.orientation().logicalLocalToPhysical(
                sourceLogicalX, sourceLogicalY, sourceLogicalZ, new Vector3d());

        double destinationPhysicalX = sourcePhysical.x - physicalFace.getStepX();
        double destinationPhysicalY = sourcePhysical.y - physicalFace.getStepY();
        double destinationPhysicalZ = sourcePhysical.z - physicalFace.getStepZ();
        Vector3d destinationLogical = destinationAssembly.orientation().physicalLocalToLogical(
                clampFrameCoordinate(destinationPhysicalX),
                clampFrameCoordinate(destinationPhysicalY),
                clampFrameCoordinate(destinationPhysicalZ),
                new Vector3d());

        BlockPos destinationMini = destinationGlobal.subtract(destinationSubLevel.getPlot().getCenterBlock());
        BlockPos destinationCell = MiniCoordinateMapper.cellInFrame(destinationMini);
        return new Vec3(
                clampWithinCell(destinationLogical.x * 2.0 - destinationCell.getX()),
                clampWithinCell(destinationLogical.y * 2.0 - destinationCell.getY()),
                clampWithinCell(destinationLogical.z * 2.0 - destinationCell.getZ()));
    }

    private static <T> T withBypass(Supplier<T> action) {
        int previousBypass = BYPASS_DEPTH.get();
        BYPASS_DEPTH.set(previousBypass + 1);
        try {
            return action.get();
        } finally {
            if (previousBypass == 0) BYPASS_DEPTH.remove(); else BYPASS_DEPTH.set(previousBypass);
        }
    }

    private static CellSelection toLogicalSelection(MechanismAssembly assembly, CellSelection physical) {
        double px = (physical.x() + physical.localX()) * .5;
        double py = (physical.y() + physical.localY()) * .5;
        double pz = (physical.z() + physical.localZ()) * .5;
        Vector3d logical = assembly.orientation().physicalLocalToLogical(px, py, pz, new Vector3d());
        double lx = clampUnit(logical.x), ly = clampUnit(logical.y), lz = clampUnit(logical.z);
        int x = half(lx), y = half(ly), z = half(lz);
        return new CellSelection(x, y, z,
                withinSelectedHalf(lx, x), withinSelectedHalf(ly, y), withinSelectedHalf(lz, z));
    }

    static CellSelection selectDirectCell(BlockPos framePos, Vec3 hitLocation) {
        double localX = clampUnit(hitLocation.x - framePos.getX());
        double localY = clampUnit(hitLocation.y - framePos.getY());
        double localZ = clampUnit(hitLocation.z - framePos.getZ());
        int x = half(localX), y = half(localY), z = half(localZ);
        return new CellSelection(x, y, z,
                withinSelectedHalf(localX, x), withinSelectedHalf(localY, y), withinSelectedHalf(localZ, z));
    }

    static CellSelection selectBoundaryCell(BlockPos framePos, Direction directionIntoFrame, Vec3 hitLocation) {
        double localX = clampUnit(hitLocation.x - framePos.getX());
        double localY = clampUnit(hitLocation.y - framePos.getY());
        double localZ = clampUnit(hitLocation.z - framePos.getZ());
        int x = half(localX), y = half(localY), z = half(localZ);
        double cellX = withinSelectedHalf(localX, x);
        double cellY = withinSelectedHalf(localY, y);
        double cellZ = withinSelectedHalf(localZ, z);
        switch (directionIntoFrame.getAxis()) {
            case X -> { x = directionIntoFrame.getStepX() > 0 ? 0 : 1; cellX = .5; }
            case Y -> { y = directionIntoFrame.getStepY() > 0 ? 0 : 1; cellY = .5; }
            case Z -> { z = directionIntoFrame.getStepZ() > 0 ? 0 : 1; cellZ = .5; }
        }
        return new CellSelection(x, y, z, cellX, cellY, cellZ);
    }

    static BlockPos virtualSupportPosition(BlockPos target, Direction directionIntoFrame) {
        return target.relative(directionIntoFrame.getOpposite());
    }

    static boolean isInteriorFacingFrameHit(BlockPos framePos, Direction face, Vec3 hitLocation) {
        double probe;
        double min;
        switch (face.getAxis()) {
            case X -> { probe = hitLocation.x + face.getStepX() * FACE_PROBE_EPSILON; min = framePos.getX(); }
            case Y -> { probe = hitLocation.y + face.getStepY() * FACE_PROBE_EPSILON; min = framePos.getY(); }
            case Z -> { probe = hitLocation.z + face.getStepZ() * FACE_PROBE_EPSILON; min = framePos.getZ(); }
            default -> throw new IllegalStateException("Unexpected direction axis " + face.getAxis());
        }
        return probe > min + HIT_EPSILON && probe < min + 1.0 - HIT_EPSILON;
    }

    private static Vec3 syntheticHitLocation(BlockPos clickedPos, Direction clickedFace, CellSelection selection) {
        double x = clickedPos.getX() + selection.localX();
        double y = clickedPos.getY() + selection.localY();
        double z = clickedPos.getZ() + selection.localZ();
        switch (clickedFace) {
            case DOWN -> y = clickedPos.getY() + HIT_EPSILON;
            case UP -> y = clickedPos.getY() + 1.0 - HIT_EPSILON;
            case NORTH -> z = clickedPos.getZ() + HIT_EPSILON;
            case SOUTH -> z = clickedPos.getZ() + 1.0 - HIT_EPSILON;
            case WEST -> x = clickedPos.getX() + HIT_EPSILON;
            case EAST -> x = clickedPos.getX() + 1.0 - HIT_EPSILON;
        }
        return new Vec3(x, y, z);
    }

    private static int half(double coordinate) { return coordinate >= .5 ? 1 : 0; }

    private static double withinSelectedHalf(double coordinate, int half) {
        double value = half == 0 ? coordinate * 2.0 : (coordinate - .5) * 2.0;
        return clampWithinCell(value);
    }

    private static double clampWithinCell(double value) {
        return Math.max(HIT_EPSILON, Math.min(1.0 - HIT_EPSILON, value));
    }

    private static double clampFrameCoordinate(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0 - HIT_EPSILON, value));
    }

    static record CellSelection(int x, int y, int z, double localX, double localY, double localZ) {}
}
