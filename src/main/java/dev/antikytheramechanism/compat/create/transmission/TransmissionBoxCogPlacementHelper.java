package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogwheelBlockItem;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.CreateCogwheelBlockItemPlacementAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.LazySubLevelLifecycle;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Exposes each configured Transmission Box corner cog as a half-scale Create placement-helper source.
 *
 * <p>The placement geometry itself is never recreated here. Each held {@link CogwheelBlockItem}
 * already owns Create's registered SmallCogHelper or LargeCogHelper; an accessor exposes that helper
 * id and this adapter invokes it against a synthetic cog in the managed mini lattice. The returned
 * PlacementOffset then goes through the same FrameMask/cross-Frame placement path as ordinary mini
 * Create helpers.</p>
 */
public final class TransmissionBoxCogPlacementHelper implements IPlacementHelper {
    private static final TransmissionBoxCogPlacementHelper INSTANCE = new TransmissionBoxCogPlacementHelper();
    private static volatile int helperId = -1;
    private static volatile @Nullable ClientBridge clientBridge;

    private @Nullable Preview pendingPreview;

    private TransmissionBoxCogPlacementHelper() {
    }

    public static void register() {
        if (helperId >= 0) {
            return;
        }
        synchronized (TransmissionBoxCogPlacementHelper.class) {
            if (helperId < 0) {
                helperId = PlacementHelpers.register(INSTANCE);
            }
        }
    }

    public static void registerClientBridge(ClientBridge bridge) {
        clientBridge = Objects.requireNonNull(bridge, "bridge");
    }

    public static boolean supportsItem(ItemStack stack) {
        IPlacementHelper helper = nativeCogHelper(stack);
        return helper != null && helper.matchesItem(stack);
    }

    /**
     * Handles an actual block use on a configured corner cog. PASS means the hit was not on a cog
     * source and allows the MICRO-shaft helper (or ordinary block interaction) to try instead.
     */
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

        CogSource source = resolveSource(level, boxPos, hit);
        if (source == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.FAIL;
        }

        PlacementOffset offset = nativeOffset(player, serverLevel, stack, source);
        if (!offset.isSuccessful()) {
            return ItemInteractionResult.FAIL;
        }

        PhysicalTarget target = resolvePhysicalTarget(serverLevel, source, offset.getBlockPos());
        if (target == null) {
            // A native Create suggestion that leaves every Mechanism Frame is deliberately cancelled.
            return ItemInteractionResult.FAIL;
        }

        BlockState logicalState = offset.getTransform().apply(blockItem.getBlock().defaultBlockState());
        if (!validDestination(serverLevel, stack, source, target, logicalState)) {
            return ItemInteractionResult.FAIL;
        }

        BlockHitResult managedRay = new BlockHitResult(
                Vec3.atCenterOf(source.ownedRaySource()),
                source.logicalHitFace(),
                source.ownedRaySource(),
                false);
        return offset.placeInWorld(serverLevel, blockItem, player, hand, managedRay);
    }

    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return TransmissionBoxCogPlacementHelper::supportsItem;
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

    /** Create placement-guide query. The actual ghost is rendered at half scale by the client bridge. */
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

        CogSource source = resolveSource(world, pos, ray);
        if (source == null) {
            return PlacementOffset.fail();
        }
        PlacementOffset nativeOffset = nativeOffset(player, world, heldItem, source);
        if (!nativeOffset.isSuccessful()) {
            return PlacementOffset.fail();
        }

        PhysicalTarget target = resolvePhysicalTarget(world, source, nativeOffset.getBlockPos());
        if (target == null) {
            return PlacementOffset.fail();
        }
        BlockState logicalState = nativeOffset.getTransform().apply(blockItem.getBlock().defaultBlockState());
        if (!validClientDestination(world, heldItem, source, target, logicalState)) {
            return PlacementOffset.fail();
        }

        pendingPreview = new Preview(
                pos.immutable(),
                target.framePosition(),
                target.physicalCell(),
                rotateLogicalStateToPhysical(logicalState, source.orientation()));
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
            CogSource source) {
        IPlacementHelper helper = nativeCogHelper(held);
        if (helper == null) {
            return PlacementOffset.fail();
        }
        BlockState syntheticCog = (source.mode() == TransmissionBoxCogMode.LARGE
                ? AllBlocks.LARGE_COGWHEEL.getDefaultState()
                : AllBlocks.COGWHEEL.getDefaultState())
                .setValue(RotatedPillarKineticBlock.AXIS, source.logicalCogAxis());
        BlockHitResult syntheticRay = new BlockHitResult(
                new Vec3(
                        source.sourcePlot().getX() + source.logicalHitWithinCell().x,
                        source.sourcePlot().getY() + source.logicalHitWithinCell().y,
                        source.sourcePlot().getZ() + source.logicalHitWithinCell().z),
                source.logicalHitFace(),
                source.sourcePlot(),
                true);
        if (level instanceof ServerLevel) {
            return MiniWorldEnvironment.withVirtualReads(
                    () -> helper.getOffset(player, level, syntheticCog, source.sourcePlot(), syntheticRay));
        }
        return helper.getOffset(player, level, syntheticCog, source.sourcePlot(), syntheticRay);
    }

    private static @Nullable IPlacementHelper nativeCogHelper(ItemStack stack) {
        if (!(stack.getItem() instanceof CogwheelBlockItem cogItem)) {
            return null;
        }
        CreateCogwheelBlockItemPlacementAccessor accessor =
                (CreateCogwheelBlockItemPlacementAccessor) (Object) cogItem;
        return PlacementHelpers.get(accessor.antikytheramechanism$getPlacementHelperId());
    }

    private static @Nullable CogSource resolveSource(Level level, BlockPos boxPos, BlockHitResult hit) {
        if (!(level.getBlockEntity(boxPos) instanceof TransmissionBoxBlockEntity box)) {
            return null;
        }
        TransmissionBoxHitTarget hitTarget = TransmissionBoxHitTarget.resolve(hit, box);
        if (hitTarget.kind() != TransmissionBoxHitTarget.Kind.CORNER || hitTarget.corner() == null) {
            return null;
        }
        TransmissionBoxCorner corner = hitTarget.corner();
        TransmissionBoxCogMode mode = box.cornerMode(corner);
        if (mode == TransmissionBoxCogMode.EMPTY) {
            return null;
        }

        BlockPos sourcePhysicalMini = cogCell(boxPos, corner);
        BlockPos anchorFrame = findAnchorFrame(level, boxPos, corner, hit.getDirection());
        if (anchorFrame == null
                || !(level.getBlockEntity(anchorFrame) instanceof MechanismFrameBlockEntity frameEntity)
                || frameEntity.getAssemblyId() == null) {
            return null;
        }

        FrameOrientation orientation;
        BlockPos sourcePlot;
        BlockPos ownedRaySource;
        if (level instanceof ServerLevel serverLevel) {
            MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
            if (manager.isFrameLifecycleLocked(anchorFrame)) {
                return null;
            }
            MechanismAssembly assembly = manager.getAssemblyAt(anchorFrame).orElse(null);
            if (assembly == null
                    || !frameEntity.getAssemblyId().equals(assembly.id())
                    || manager.pendingContraptionMove(assembly.id()).isPresent()
                    || manager.pendingPistonMove(assembly.id()).isPresent()
                    || manager.isContentRecoveryLocked(assembly.id())
                    || !MechanismAssemblyHost.sameResolvedHost(serverLevel, boxPos, anchorFrame)
                    || !MechanismAssemblyHost.boundaryIsAligned(serverLevel, assembly, 1.0E-5)) {
                return null;
            }
            ServerSubLevel child = MechanismSubLevelService.ensureForContent(serverLevel, assembly);
            if (child == null) {
                return null;
            }
            LazySubLevelLifecycle.requestRetirementCheck(serverLevel, assembly.id());
            orientation = assembly.orientation();
            BlockPos sourceMini = extrapolatedLogicalMini(
                    assembly.logicalFrameOffset(anchorFrame), orientation, anchorFrame, sourcePhysicalMini);
            sourcePlot = MechanismSubLevelService.toPlotPosition(child, sourceMini);
            BlockPos ownedMini = MiniCoordinateMapper.physicalFrameCellToMini(
                    assembly,
                    anchorFrame,
                    clampCell(sourcePhysicalMini.getX() - anchorFrame.getX() * 2),
                    clampCell(sourcePhysicalMini.getY() - anchorFrame.getY() * 2),
                    clampCell(sourcePhysicalMini.getZ() - anchorFrame.getZ() * 2));
            ownedRaySource = MechanismSubLevelService.toPlotPosition(child, ownedMini);
        } else {
            ClientBridge bridge = clientBridge;
            if (bridge == null) {
                return null;
            }
            orientation = frameEntity.getFrameOrientation();
            BlockPos sourceMini = extrapolatedLogicalMini(
                    frameEntity.getLogicalFrameOffset(), orientation, anchorFrame, sourcePhysicalMini);
            sourcePlot = bridge.resolveManagedPlotTarget(level, frameEntity.getAssemblyId(), sourceMini);
            BlockPos localPhysical = sourcePhysicalMini.offset(
                    -anchorFrame.getX() * 2,
                    -anchorFrame.getY() * 2,
                    -anchorFrame.getZ() * 2);
            BlockPos ownedLogicalCell = orientation.physicalCellToLogical(
                    clampCell(localPhysical.getX()),
                    clampCell(localPhysical.getY()),
                    clampCell(localPhysical.getZ()));
            BlockPos ownedMini = frameEntity.getLogicalFrameOffset()
                    .multiply(MiniCoordinateMapper.CELLS_PER_FRAME_AXIS)
                    .offset(ownedLogicalCell);
            ownedRaySource = bridge.resolveManagedPlotTarget(level, frameEntity.getAssemblyId(), ownedMini);
            if (sourcePlot == null || ownedRaySource == null) {
                return null;
            }
        }

        Direction physicalPositiveAxis = Direction.fromAxisAndDirection(
                box.structuralAxis(), Direction.AxisDirection.POSITIVE);
        Direction logicalPositiveAxis = orientation.toLogical(physicalPositiveAxis);
        Direction logicalHitFace = orientation.toLogical(hit.getDirection());
        if (logicalPositiveAxis == null || logicalHitFace == null) {
            return null;
        }

        double localPhysicalX = clampUnit(hit.getLocation().x * 2.0 - sourcePhysicalMini.getX());
        double localPhysicalY = clampUnit(hit.getLocation().y * 2.0 - sourcePhysicalMini.getY());
        double localPhysicalZ = clampUnit(hit.getLocation().z * 2.0 - sourcePhysicalMini.getZ());
        Vector3d logicalHit = orientation.physicalLocalToLogical(
                localPhysicalX, localPhysicalY, localPhysicalZ, new Vector3d());

        return new CogSource(
                boxPos.immutable(),
                anchorFrame.immutable(),
                corner,
                mode,
                sourcePhysicalMini.immutable(),
                orientation,
                logicalPositiveAxis.getAxis(),
                logicalHitFace,
                sourcePlot.immutable(),
                ownedRaySource.immutable(),
                new Vec3(logicalHit.x, logicalHit.y, logicalHit.z));
    }

    private static @Nullable BlockPos findAnchorFrame(
            Level level,
            BlockPos boxPos,
            TransmissionBoxCorner corner,
            Direction clickedFace) {
        BlockPos preferred = boxPos.relative(clickedFace);
        if (validAnchorFrame(level, boxPos, preferred)) {
            return preferred.immutable();
        }

        int sx = corner.sign(Direction.Axis.X);
        int sy = corner.sign(Direction.Axis.Y);
        int sz = corner.sign(Direction.Axis.Z);
        for (int mask = 1; mask < 8; mask++) {
            BlockPos candidate = boxPos.offset(
                    (mask & 1) != 0 ? sx : 0,
                    (mask & 2) != 0 ? sy : 0,
                    (mask & 4) != 0 ? sz : 0);
            if (validAnchorFrame(level, boxPos, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static boolean validAnchorFrame(Level level, BlockPos boxPos, BlockPos framePos) {
        return MechanismAssemblyHost.samePhysicalHost(level, boxPos, framePos)
                && level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get())
                && level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity frame
                && frame.getAssemblyId() != null;
    }

    private static BlockPos extrapolatedLogicalMini(
            BlockPos logicalFrameOffset,
            FrameOrientation orientation,
            BlockPos framePos,
            BlockPos physicalMini) {
        BlockPos localPhysical = physicalMini.offset(
                -framePos.getX() * 2,
                -framePos.getY() * 2,
                -framePos.getZ() * 2);
        BlockPos localLogical = orientation.physicalCellToLogical(
                localPhysical.getX(), localPhysical.getY(), localPhysical.getZ());
        return logicalFrameOffset.multiply(MiniCoordinateMapper.CELLS_PER_FRAME_AXIS).offset(localLogical);
    }

    private static @Nullable PhysicalTarget resolvePhysicalTarget(
            Level level,
            CogSource source,
            BlockPos plotTarget) {
        BlockPos logicalDelta = plotTarget.subtract(source.sourcePlot());
        BlockPos physicalDelta = source.orientation().toPhysical(logicalDelta);
        BlockPos physicalMini = source.sourcePhysicalMini().offset(physicalDelta);
        BlockPos framePosition = frameForPhysicalMini(physicalMini);
        if (!MechanismAssemblyHost.samePhysicalHost(level, source.boxPosition(), framePosition)
                || !level.getBlockState(framePosition).is(ModRegistries.MECHANISM_FRAME.get())
                || !(level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frame)
                || frame.getAssemblyId() == null) {
            return null;
        }
        return new PhysicalTarget(framePosition.immutable(), cellForPhysicalMini(physicalMini));
    }

    private static boolean validDestination(
            ServerLevel level,
            ItemStack stack,
            CogSource source,
            PhysicalTarget target,
            BlockState sourceLogicalState) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        if (manager.isFrameLifecycleLocked(target.framePosition())) {
            return false;
        }
        MechanismAssembly destination = manager.getAssemblyAt(target.framePosition()).orElse(null);
        if (destination == null
                || manager.pendingContraptionMove(destination.id()).isPresent()
                || manager.pendingPistonMove(destination.id()).isPresent()
                || manager.isContentRecoveryLocked(destination.id())
                || !MechanismAssemblyHost.sameResolvedHost(level, source.boxPosition(), target.framePosition())
                || !MechanismAssemblyHost.boundaryIsAligned(level, destination, 1.0E-5)) {
            return false;
        }
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, destination);
        if (child == null) {
            return false;
        }
        LazySubLevelLifecycle.requestRetirementCheck(level, destination.id());
        BlockPos logicalCell = destination.orientation().physicalCellToLogical(
                target.physicalCell().getX(),
                target.physicalCell().getY(),
                target.physicalCell().getZ());
        BlockPos mini = MiniCoordinateMapper.frameToMini(
                destination,
                target.framePosition(),
                logicalCell.getX(), logicalCell.getY(), logicalCell.getZ());
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
        if (!level.getBlockState(global).canBeReplaced()) {
            return false;
        }

        BlockState physicalState = rotateLogicalStateToPhysical(sourceLogicalState, source.orientation());
        if (!physicalState.hasProperty(RotatedPillarKineticBlock.AXIS)) {
            return false;
        }
        Direction physicalPositive = Direction.fromAxisAndDirection(
                physicalState.getValue(RotatedPillarKineticBlock.AXIS),
                Direction.AxisDirection.POSITIVE);
        Direction destinationLogicalPositive = destination.orientation().toLogical(physicalPositive);
        if (destinationLogicalPositive == null) {
            return false;
        }
        boolean large = ICogWheel.isLargeCogItem(stack);
        return MiniWorldEnvironment.withVirtualReads(() -> CogWheelBlock.isValidCogwheelPosition(
                large,
                level,
                global,
                destinationLogicalPositive.getAxis()));
    }

    private static boolean validClientDestination(
            Level level,
            ItemStack stack,
            CogSource source,
            PhysicalTarget target,
            BlockState sourceLogicalState) {
        if (!(level.getBlockEntity(target.framePosition()) instanceof MechanismFrameBlockEntity frame)
                || frame.getAssemblyId() == null) {
            return false;
        }
        ClientBridge bridge = clientBridge;
        if (bridge == null) {
            return false;
        }
        FrameOrientation destinationOrientation = frame.getFrameOrientation();
        BlockPos destinationLogicalCell = destinationOrientation.physicalCellToLogical(
                target.physicalCell().getX(),
                target.physicalCell().getY(),
                target.physicalCell().getZ());
        BlockPos destinationMini = frame.getLogicalFrameOffset()
                .multiply(MiniCoordinateMapper.CELLS_PER_FRAME_AXIS)
                .offset(destinationLogicalCell);
        BlockPos destinationGlobal = bridge.resolveManagedPlotTarget(
                level, frame.getAssemblyId(), destinationMini);
        if (destinationGlobal == null) {
            // No client child usually means the destination Frame is still empty; the server will
            // create/address it and perform the authoritative native validity check.
            return true;
        }
        if (!level.getBlockState(destinationGlobal).canBeReplaced()) {
            return false;
        }
        BlockState physicalState = rotateLogicalStateToPhysical(sourceLogicalState, source.orientation());
        if (!physicalState.hasProperty(RotatedPillarKineticBlock.AXIS)) {
            return false;
        }
        Direction physicalPositive = Direction.fromAxisAndDirection(
                physicalState.getValue(RotatedPillarKineticBlock.AXIS),
                Direction.AxisDirection.POSITIVE);
        Direction destinationLogicalPositive = destinationOrientation.toLogical(physicalPositive);
        return destinationLogicalPositive != null
                && CogWheelBlock.isValidCogwheelPosition(
                        ICogWheel.isLargeCogItem(stack),
                        level,
                        destinationGlobal,
                        destinationLogicalPositive.getAxis());
    }

    private static BlockState rotateLogicalStateToPhysical(
            BlockState logicalState,
            FrameOrientation orientation) {
        Rotation rotation = switch (orientation.front()) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException(
                    "Static Frame front is not horizontal: " + orientation.front());
        };
        return logicalState.rotate(rotation);
    }

    private static BlockPos cogCell(BlockPos box, TransmissionBoxCorner corner) {
        return new BlockPos(
                box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.X),
                box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.Y),
                box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + corner.cell(Direction.Axis.Z));
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

    private static int clampCell(int value) {
        return value <= 0 ? 0 : 1;
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public interface ClientBridge {
        @Nullable BlockPos resolveManagedPlotTarget(Level level, UUID assemblyId, BlockPos logicalMini);
        void renderPreview(Preview preview);
    }

    public record Preview(
            BlockPos boxPosition,
            BlockPos destinationFrame,
            BlockPos physicalCell,
            BlockState physicalGhostState) {
    }

    private record CogSource(
            BlockPos boxPosition,
            BlockPos anchorFrame,
            TransmissionBoxCorner corner,
            TransmissionBoxCogMode mode,
            BlockPos sourcePhysicalMini,
            FrameOrientation orientation,
            Direction.Axis logicalCogAxis,
            Direction logicalHitFace,
            BlockPos sourcePlot,
            BlockPos ownedRaySource,
            Vec3 logicalHitWithinCell) {
    }

    private record PhysicalTarget(BlockPos framePosition, BlockPos physicalCell) {
    }
}
