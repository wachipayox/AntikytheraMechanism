package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Adapts Create's own shaft placement helper to the four half-scale shaft outputs of a Transmission
 * Box MICRO face.
 *
 * <p>No placement rule is reimplemented here. The actual offset/axis selection is delegated to
 * {@link ShaftBlock#placementHelperId}. Antikythera only maps one selected macro-face quadrant into
 * the managed Frame lattice and then hands the resulting {@link PlacementOffset} back to Catnip.
 * Server placement deliberately uses an owned mini cell as the synthetic ray source, so the existing
 * PlacementOffset FrameMask transaction remains authoritative for same-Frame writes, cross-Frame
 * redirection, outside-mask rejection and failed-write item preservation.</p>
 */
public final class TransmissionBoxMiniPlacementHelper implements IPlacementHelper {
    private static final TransmissionBoxMiniPlacementHelper INSTANCE = new TransmissionBoxMiniPlacementHelper();
    private static final double RAY_BIAS = 0.49;
    private static volatile int helperId = -1;
    private static volatile @Nullable ClientBridge clientBridge;

    private @Nullable Preview pendingPreview;

    private TransmissionBoxMiniPlacementHelper() {
    }

    public static void register() {
        if (helperId >= 0) {
            return;
        }
        synchronized (TransmissionBoxMiniPlacementHelper.class) {
            if (helperId < 0) {
                helperId = PlacementHelpers.register(INSTANCE);
            }
        }
    }

    public static void registerClientBridge(ClientBridge bridge) {
        clientBridge = Objects.requireNonNull(bridge, "bridge");
    }

    public static boolean supportsItem(ItemStack stack) {
        return nativeShaftHelper().matchesItem(stack);
    }

    /** Called by TransmissionBoxBlock after ordinary block interaction has selected a MICRO face. */
    public static ItemInteractionResult placeFromBox(
            ItemStack stack,
            Level level,
            BlockPos boxPos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !supportsItem(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return resolvePort(level, boxPos, hit) != null
                    ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.FAIL;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.FAIL;
        }

        ResolvedPort port = resolvePort(serverLevel, boxPos, hit);
        if (port == null || port.firstPlotTarget() == null) {
            return ItemInteractionResult.FAIL;
        }

        PlacementOffset offset = nativeOffset(
                player,
                serverLevel,
                stack,
                port,
                port.firstPlotTarget());
        if (!offset.isSuccessful()) {
            return ItemInteractionResult.FAIL;
        }

        PhysicalTarget physicalTarget = resolvePhysicalTarget(
                serverLevel,
                port,
                offset.getBlockPos());
        if (physicalTarget == null) {
            return ItemInteractionResult.FAIL;
        }

        BlockPos placementSource = chooseOwnedPlacementSource(
                serverLevel,
                port,
                offset.getBlockPos());
        if (!ManagedMiniPlacementTargets.isOwnedTarget(
                        serverLevel, placementSource, offset.getBlockPos())
                && ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                                serverLevel, placementSource, offset.getBlockPos())
                        .isEmpty()) {
            return ItemInteractionResult.FAIL;
        }

        BlockHitResult managedRay = syntheticRay(placementSource, port.logicalOutward());
        return offset.placeInWorld(
                serverLevel,
                blockItem,
                player,
                hand,
                managedRay);
    }

    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return TransmissionBoxMiniPlacementHelper::supportsItem;
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return state -> state.is(CreateTransmissionRegistries.TRANSMISSION_BOX.get());
    }

    @Override
    public PlacementOffset getOffset(
            Player player,
            Level world,
            BlockState state,
            BlockPos pos,
            BlockHitResult ray) {
        return PlacementOffset.fail();
    }

    @Override
    public PlacementOffset getOffset(
            Player player,
            Level world,
            BlockState state,
            BlockPos pos,
            BlockHitResult ray,
            ItemStack heldItem) {
        pendingPreview = null;
        if (!(heldItem.getItem() instanceof BlockItem blockItem) || !supportsItem(heldItem)) {
            return PlacementOffset.fail();
        }

        ResolvedPort port = resolvePort(world, pos, ray);
        if (port == null) {
            return PlacementOffset.fail();
        }

        if (port.firstPlotTarget() != null) {
            PlacementOffset nativeOffset = nativeOffset(
                    player,
                    world,
                    heldItem,
                    port,
                    port.firstPlotTarget());
            if (nativeOffset.isSuccessful()) {
                PhysicalTarget physicalTarget = resolvePhysicalTarget(
                        world,
                        port,
                        nativeOffset.getBlockPos());
                if (physicalTarget != null) {
                    BlockState logicalGhostState = nativeOffset.getTransform()
                            .apply(blockItem.getBlock().defaultBlockState());
                    pendingPreview = new Preview(
                            pos.immutable(),
                            physicalTarget.framePosition(),
                            physicalTarget.physicalCell(),
                            port.sourceOrientation(),
                            logicalGhostState);
                }
            }
        }

        if (pendingPreview == null) {
            BlockState fallback = blockItem.getBlock().defaultBlockState();
            if (!fallback.hasProperty(RotatedPillarKineticBlock.AXIS)) {
                return PlacementOffset.fail();
            }
            fallback = fallback.setValue(
                    RotatedPillarKineticBlock.AXIS,
                    port.logicalOutward().getAxis());
            PhysicalTarget first = resolvePhysicalTargetFromMini(
                    world,
                    port.boxPosition(),
                    port.firstPhysicalMini());
            if (first == null) {
                return PlacementOffset.fail();
            }
            pendingPreview = new Preview(
                    pos.immutable(),
                    first.framePosition(),
                    first.physicalCell(),
                    port.sourceOrientation(),
                    fallback);
        }

        return PlacementOffset.success(pos);
    }

    @Override
    public void renderAt(
            BlockPos pos,
            BlockState state,
            BlockHitResult ray,
            PlacementOffset offset) {
        Preview preview = pendingPreview;
        ClientBridge bridge = clientBridge;
        if (preview != null && bridge != null) {
            bridge.renderPreview(preview);
        }
    }

    private static PlacementOffset nativeOffset(
            Player player,
            Level level,
            ItemStack held,
            ResolvedPort port,
            BlockPos firstPlotTarget) {
        BlockState virtualShaft = AllBlocks.SHAFT.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, port.logicalOutward().getAxis());
        BlockPos syntheticSource = firstPlotTarget.relative(port.logicalOutward().getOpposite());
        BlockHitResult syntheticRay = syntheticRay(syntheticSource, port.logicalOutward());
        return nativeShaftHelper().getOffset(
                player,
                level,
                virtualShaft,
                syntheticSource,
                syntheticRay);
    }

    private static IPlacementHelper nativeShaftHelper() {
        return PlacementHelpers.get(ShaftBlock.placementHelperId);
    }

    private static @Nullable ResolvedPort resolvePort(
            Level level,
            BlockPos boxPos,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(boxPos) instanceof TransmissionBoxBlockEntity box)) {
            return null;
        }
        Direction face = hit.getDirection();
        if (box.faceMode(face) != TransmissionBoxFaceMode.MICRO) {
            return null;
        }

        BlockPos physicalMini = selectedPhysicalMini(boxPos, face, hit.getLocation());
        TransmissionBoxCorner corner = cornerForPhysicalMini(boxPos, face, physicalMini);
        if (box.cornerMode(corner) != TransmissionBoxCogMode.EMPTY) {
            return null;
        }

        BlockPos framePosition = frameForPhysicalMini(physicalMini);
        if (!MechanismAssemblyHost.samePhysicalHost(level, boxPos, framePosition)
                || !level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())
                || !(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frameEntity)
                || frameEntity.getAssemblyId() == null) {
            return null;
        }

        BlockPos physicalCell = cellForPhysicalMini(physicalMini);
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (manager.isFrameLifecycleLocked(framePosition)) {
                return null;
            }
            MechanismAssembly assembly = manager.getAssemblyAt(framePosition).orElse(null);
            if (assembly == null
                    || !frameEntity.getAssemblyId().equals(assembly.id())
                    || manager.pendingContraptionMove(assembly.id()).isPresent()
                    || manager.pendingPistonMove(assembly.id()).isPresent()
                    || manager.isContentRecoveryLocked(assembly.id())
                    || !MechanismAssemblyHost.sameResolvedHost(serverLevel, boxPos, framePosition)
                    || !MechanismAssemblyHost.boundaryIsAligned(serverLevel, assembly, 1.0E-5)) {
                return null;
            }

            BlockPos logicalCell = assembly.orientation().physicalCellToLogical(
                    physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
            Direction logicalOutward = assembly.orientation().toLogical(face);
            if (logicalOutward == null) {
                return null;
            }
            ServerSubLevel child = MechanismSubLevelService.ensureForContent(serverLevel, assembly);
            if (child == null) {
                return null;
            }
            LazySubLevelLifecycle.requestRetirementCheck(serverLevel, assembly.id());
            BlockPos mini = MiniCoordinateMapper.frameToMini(
                    assembly,
                    framePosition,
                    logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
            if (!MechanismSubLevelService.canAddressMiniPosition(serverLevel, child, mini)) {
                return null;
            }
            return new ResolvedPort(
                    boxPos.immutable(),
                    framePosition.immutable(),
                    physicalMini.immutable(),
                    physicalCell.immutable(),
                    assembly.orientation(),
                    logicalOutward,
                    MechanismSubLevelService.toPlotPosition(child, mini));
        }

        FrameOrientation orientation = frameEntity.getFrameOrientation();
        BlockPos logicalCell = orientation.physicalCellToLogical(
                physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
        Direction logicalOutward = orientation.toLogical(face);
        if (logicalOutward == null) {
            return null;
        }
        BlockPos logicalMini = frameEntity.getLogicalFrameOffset()
                .multiply(MiniCoordinateMapper.CELLS_PER_FRAME_AXIS)
                .offset(logicalCell);
        ClientBridge bridge = clientBridge;
        BlockPos firstPlotTarget = bridge == null
                ? null
                : bridge.resolveManagedPlotTarget(level, frameEntity.getAssemblyId(), logicalMini);
        return new ResolvedPort(
                boxPos.immutable(),
                framePosition.immutable(),
                physicalMini.immutable(),
                physicalCell.immutable(),
                orientation,
                logicalOutward,
                firstPlotTarget == null ? null : firstPlotTarget.immutable());
    }

    private static @Nullable PhysicalTarget resolvePhysicalTarget(
            Level level,
            ResolvedPort port,
            BlockPos plotTarget) {
        BlockPos firstPlot = port.firstPlotTarget();
        if (firstPlot == null) {
            return null;
        }
        BlockPos delta = plotTarget.subtract(firstPlot);
        Direction logical = port.logicalOutward();
        int distance = logical.getAxis().choose(delta.getX(), delta.getY(), delta.getZ());
        if (distance < 0
                || delta.getX() != logical.getStepX() * distance
                || delta.getY() != logical.getStepY() * distance
                || delta.getZ() != logical.getStepZ() * distance) {
            return null;
        }
        BlockPos physical = port.firstPhysicalMini().offset(
                port.physicalFace().getStepX() * distance,
                port.physicalFace().getStepY() * distance,
                port.physicalFace().getStepZ() * distance);
        return resolvePhysicalTargetFromMini(level, port.boxPosition(), physical);
    }

    private static @Nullable PhysicalTarget resolvePhysicalTargetFromMini(
            Level level,
            BlockPos boxPosition,
            BlockPos physicalMini) {
        BlockPos framePosition = frameForPhysicalMini(physicalMini);
        if (!MechanismAssemblyHost.samePhysicalHost(level, boxPosition, framePosition)
                || !level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())
                || !(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frameEntity)
                || frameEntity.getAssemblyId() == null) {
            return null;
        }
        if (level instanceof ServerLevel serverLevel
                && MechanismAssemblyManager.get(serverLevel).isFrameLifecycleLocked(framePosition)) {
            return null;
        }
        return new PhysicalTarget(framePosition.immutable(), cellForPhysicalMini(physicalMini));
    }

    private static BlockPos chooseOwnedPlacementSource(
            ServerLevel level,
            ResolvedPort port,
            BlockPos target) {
        BlockPos previous = target.relative(port.logicalOutward().getOpposite());
        if (ManagedMiniPlacementTargets.isManagedSource(level, previous)
                && ManagedMiniPlacementTargets.isOwnedTarget(level, previous, previous)) {
            return previous;
        }
        return port.firstPlotTarget();
    }

    private static BlockHitResult syntheticRay(BlockPos source, Direction outward) {
        Vec3 location = Vec3.atCenterOf(source).add(
                outward.getStepX() * RAY_BIAS,
                outward.getStepY() * RAY_BIAS,
                outward.getStepZ() * RAY_BIAS);
        return new BlockHitResult(location, outward, source, false);
    }

    private static BlockPos selectedPhysicalMini(BlockPos box, Direction face, Vec3 hit) {
        int minX = box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minY = box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minZ = box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int x = minX + half(hit.x - box.getX());
        int y = minY + half(hit.y - box.getY());
        int z = minZ + half(hit.z - box.getZ());
        switch (face.getAxis()) {
            case X -> x = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minX + 2 : minX - 1;
            case Y -> y = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minY + 2 : minY - 1;
            case Z -> z = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? minZ + 2 : minZ - 1;
        }
        return new BlockPos(x, y, z);
    }

    private static TransmissionBoxCorner cornerForPhysicalMini(
            BlockPos box,
            Direction face,
            BlockPos mini) {
        int minX = box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minY = box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int minZ = box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        int x = face.getAxis() == Direction.Axis.X
                ? directionSign(face)
                : mini.getX() == minX ? -1 : 1;
        int y = face.getAxis() == Direction.Axis.Y
                ? directionSign(face)
                : mini.getY() == minY ? -1 : 1;
        int z = face.getAxis() == Direction.Axis.Z
                ? directionSign(face)
                : mini.getZ() == minZ ? -1 : 1;
        return TransmissionBoxCorner.fromSigns(x, y, z);
    }

    private static int half(double coordinate) {
        return coordinate < 0.5 ? 0 : 1;
    }

    private static int directionSign(Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
    }

    private static BlockPos frameForPhysicalMini(BlockPos physicalMini) {
        int cells = MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        return new BlockPos(
                Math.floorDiv(physicalMini.getX(), cells),
                Math.floorDiv(physicalMini.getY(), cells),
                Math.floorDiv(physicalMini.getZ(), cells));
    }

    private static BlockPos cellForPhysicalMini(BlockPos physicalMini) {
        int cells = MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        return new BlockPos(
                Math.floorMod(physicalMini.getX(), cells),
                Math.floorMod(physicalMini.getY(), cells),
                Math.floorMod(physicalMini.getZ(), cells));
    }

    @FunctionalInterface
    public interface PreviewRenderer {
        void render(Preview preview);
    }

    public interface ClientBridge {
        @Nullable BlockPos resolveManagedPlotTarget(Level level, UUID assemblyId, BlockPos logicalMini);
        void renderPreview(Preview preview);
    }

    public record Preview(
            BlockPos boxPosition,
            BlockPos destinationFrame,
            BlockPos physicalCell,
            FrameOrientation sourceOrientation,
            BlockState logicalGhostState) {
    }

    private record ResolvedPort(
            BlockPos boxPosition,
            BlockPos sourceFrame,
            BlockPos firstPhysicalMini,
            BlockPos physicalCell,
            FrameOrientation sourceOrientation,
            Direction logicalOutward,
            @Nullable BlockPos firstPlotTarget) {
        Direction physicalFace() {
            Direction face = sourceOrientation.toPhysical(logicalOutward);
            if (face == null) {
                throw new IllegalStateException("Static Frame orientation produced no physical shaft face");
            }
            return face;
        }
    }

    private record PhysicalTarget(BlockPos framePosition, BlockPos physicalCell) {
    }
}
