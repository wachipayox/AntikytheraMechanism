package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.FramePlacementFeedbackHooks;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.AssemblyPoseDriver;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
        int previousBypass = BYPASS_DEPTH.get();
        BYPASS_DEPTH.set(previousBypass + 1);
        try {
            if (parentSupport) {
                placementResult = MiniWorldEnvironment.withVirtualReads(
                        () -> stack.useOn(new UseOnContext(player, hand, localHit)));
            } else {
                placementResult = stack.useOn(new UseOnContext(player, hand, localHit));
            }
        } finally {
            if (previousBypass == 0) BYPASS_DEPTH.remove(); else BYPASS_DEPTH.set(previousBypass);
        }

        BlockState after = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        boolean placed = placementResult.consumesAction() && !after.isAir() && !after.equals(before);
        if (placed) manager.refreshFrame(level, framePos);
        return placed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
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
        return Math.max(HIT_EPSILON, Math.min(1.0 - HIT_EPSILON, value));
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0 - HIT_EPSILON, value));
    }

    static record CellSelection(int x, int y, int z, double localX, double localY, double localZ) {}
}
