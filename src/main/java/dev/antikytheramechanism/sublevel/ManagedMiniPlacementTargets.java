package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Preflight for placement paths that choose targets from an already-managed mini block. */
public final class ManagedMiniPlacementTargets {
    private static final double ALIGNMENT_EPSILON = 1.0E-6;
    private static volatile ClientTargetResolver clientTargetResolver =
            (level, source, target) -> ClientFrameTarget.unknown();

    private ManagedMiniPlacementTargets() {
    }

    /**
     * Client-only classification of the physical Frame reached by a source-child plot coordinate.
     * Common/server code owns the API so callers never need to link client-only Sable classes.
     */
    public enum ClientTargetKind {
        SAME_ASSEMBLY,
        OTHER_ASSEMBLY,
        NO_FRAME,
        UNKNOWN
    }

    public record ClientFrameTarget(ClientTargetKind kind, @Nullable BlockPos framePosition) {
        public ClientFrameTarget {
            Objects.requireNonNull(kind, "kind");
        }

        public static ClientFrameTarget sameAssembly(BlockPos framePosition) {
            return new ClientFrameTarget(ClientTargetKind.SAME_ASSEMBLY, framePosition.immutable());
        }

        public static ClientFrameTarget otherAssembly(BlockPos framePosition) {
            return new ClientFrameTarget(ClientTargetKind.OTHER_ASSEMBLY, framePosition.immutable());
        }

        public static ClientFrameTarget noFrame() {
            return new ClientFrameTarget(ClientTargetKind.NO_FRAME, null);
        }

        public static ClientFrameTarget unknown() {
            return new ClientFrameTarget(ClientTargetKind.UNKNOWN, null);
        }
    }

    @FunctionalInterface
    public interface ClientTargetResolver {
        ClientFrameTarget resolve(Level level, BlockPos source, BlockPos target);
    }

    /** Registered from the client bootstrap; dedicated servers keep the UNKNOWN no-op resolver. */
    public static void registerClientTargetResolver(ClientTargetResolver resolver) {
        clientTargetResolver = Objects.requireNonNull(resolver, "resolver");
    }

    public static ClientFrameTarget resolveClientFrameTarget(Level level, BlockPos source, BlockPos target) {
        return clientTargetResolver.resolve(level, source, target);
    }

    /** True when {@code source} is a user-facing position inside an Antikythera SubLevel. */
    public static boolean isManagedSource(Level level, BlockPos source) {
        return MiniWorldEnvironment.isManagedSubLevel(Sable.HELPER.getContaining(level, source));
    }

    /**
     * Validates a placement target relative to the managed SubLevel containing {@code source}.
     *
     * <p>Server-side ownership is always checked against the authoritative FrameMask. On the client,
     * a target in the same logical 2x2x2 Frame volume as the clicked mini cell is intrinsically owned.
     * Crossing a Frame-volume boundary is classified against synchronized physical Frame block
     * entities: another Frame in the same assembly is still owned, a Frame belonging to a different
     * assembly is deliberately not, and a real empty macro-side target is not. Only when client host
     * reconstruction is temporarily unavailable do we retain the old physical projection fallback.
     * This distinction prevents a differently-yawed neighbor Frame from being treated as writable
     * storage in the source assembly's plot.</p>
     */
    public static boolean isOwnedTarget(Level level, BlockPos source, BlockPos target) {
        SubLevel containing = Sable.HELPER.getContaining(level, source);
        if (containing == null || !MiniWorldEnvironment.isManagedSubLevel(containing)) {
            return true;
        }

        if (level instanceof ServerLevel serverLevel && containing instanceof ServerSubLevel serverSubLevel) {
            return isOwnedServerTarget(serverLevel, serverSubLevel, target);
        }

        BlockPos plotCenter = containing.getPlot().getCenterBlock();
        BlockPos sourceMini = source.subtract(plotCenter);
        BlockPos targetMini = target.subtract(plotCenter);
        if (sameLogicalFrameVolume(sourceMini, targetMini)) {
            return true;
        }

        ClientFrameTarget clientTarget = resolveClientFrameTarget(level, source, target);
        switch (clientTarget.kind()) {
            case SAME_ASSEMBLY -> {
                return true;
            }
            case OTHER_ASSEMBLY, NO_FRAME -> {
                return false;
            }
            case UNKNOWN -> {
                // Fall through to the conservative legacy projection during short client tracking
                // windows where synchronized Frame BEs or host bindings are not available yet.
            }
        }

        Vec3 worldTarget = containing.logicalPose().transformPosition(Vec3.atCenterOf(target));
        BlockPos parentTarget = BlockPos.containing(worldTarget);
        return level.getBlockState(parentTarget).is(ModRegistries.MECHANISM_FRAME.get());
    }

    /**
     * Resolves the special placement-helper escape where a target outside the source FrameMask is
     * actually the corresponding mini cell of a different neighboring Mechanism Frame.
     *
     * <p>The returned position is the destination assembly's real Sable plot coordinate; callers
     * must write there rather than relaxing the source FrameMask. This preserves the hard guarantee
     * that no helper can leave an orphan block in an unowned area of the source plot. The source and
     * destination may have different static yaw, so both the cell and the helper's transformed
     * BlockState are converted source-logical -> physical -> destination-logical.</p>
     */
    public static Optional<NeighborFrameTarget> resolveNeighborFrameTarget(
            ServerLevel level,
            BlockPos source,
            BlockPos target) {
        SubLevel containing = Sable.HELPER.getContaining(level, source);
        if (!(containing instanceof ServerSubLevel sourceSubLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(sourceSubLevel)) {
            return Optional.empty();
        }

        UUID sourceOwnerId = MechanismSubLevelService.getOwnerAssemblyId(sourceSubLevel);
        if (sourceOwnerId == null) {
            return Optional.empty();
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssembly(sourceOwnerId).orElse(null);
        if (sourceAssembly == null) {
            return Optional.empty();
        }

        BlockPos sourceMini = source.subtract(sourceSubLevel.getPlot().getCenterBlock());
        BlockPos targetMini = target.subtract(sourceSubLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(sourceAssembly, sourceMini)
                || MiniCoordinateMapper.isOwnedMiniPosition(sourceAssembly, targetMini)) {
            return Optional.empty();
        }

        BlockPos sourceFrame = MiniCoordinateMapper.miniToFrame(sourceAssembly, sourceMini);
        BlockPos destinationFrame = MiniCoordinateMapper.miniToFrame(sourceAssembly, targetMini);
        if (!isNeighboringFrame(sourceFrame, destinationFrame)
                || !level.getBlockState(destinationFrame).is(ModRegistries.MECHANISM_FRAME.get())
                || manager.isFrameLifecycleLocked(sourceFrame)
                || manager.isFrameLifecycleLocked(destinationFrame)
                || !MechanismAssemblyHost.sameResolvedHost(level, sourceFrame, destinationFrame)
                || !MechanismAssemblyHost.boundaryIsAligned(level, sourceAssembly, ALIGNMENT_EPSILON)) {
            return Optional.empty();
        }

        MechanismAssembly destinationAssembly = manager.getAssemblyAt(destinationFrame).orElse(null);
        if (destinationAssembly == null
                || destinationAssembly.id().equals(sourceAssembly.id())
                || manager.pendingContraptionMove(destinationAssembly.id()).isPresent()
                || manager.pendingPistonMove(destinationAssembly.id()).isPresent()
                || manager.isContentRecoveryLocked(destinationAssembly.id())
                || !MechanismAssemblyHost.boundaryIsAligned(level, destinationAssembly, ALIGNMENT_EPSILON)) {
            return Optional.empty();
        }

        BlockPos sourceLogicalCell = MiniCoordinateMapper.cellInFrame(targetMini);
        BlockPos physicalCell = sourceAssembly.orientation().logicalCellToPhysical(
                sourceLogicalCell.getX(), sourceLogicalCell.getY(), sourceLogicalCell.getZ());
        BlockPos destinationLogicalCell = destinationAssembly.orientation().physicalCellToLogical(
                physicalCell.getX(), physicalCell.getY(), physicalCell.getZ());
        BlockPos destinationMini = MiniCoordinateMapper.frameToMini(
                destinationAssembly,
                destinationFrame,
                destinationLogicalCell.getX(),
                destinationLogicalCell.getY(),
                destinationLogicalCell.getZ());

        ServerSubLevel destinationSubLevel = MechanismSubLevelService.ensureForContent(level, destinationAssembly);
        if (destinationSubLevel == null || destinationSubLevel == sourceSubLevel
                || !MechanismSubLevelService.canAddressMiniPosition(level, destinationSubLevel, destinationMini)) {
            return Optional.empty();
        }
        LazySubLevelLifecycle.requestRetirementCheck(level, destinationAssembly.id());

        Direction sourceNorthInPhysicalSpace = sourceAssembly.orientation().toPhysical(Direction.NORTH);
        Direction sourceNorthInDestinationAxes = destinationAssembly.orientation().toLogical(sourceNorthInPhysicalSpace);
        Rotation stateRotation = switch (sourceNorthInDestinationAxes) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException(
                    "Static Frame yaw mapped horizontal NORTH onto " + sourceNorthInDestinationAxes);
        };

        Vec3 physicalSoundPosition = new Vec3(
                destinationFrame.getX() + physicalCell.getX() * 0.5 + 0.25,
                destinationFrame.getY() + physicalCell.getY() * 0.5 + 0.25,
                destinationFrame.getZ() + physicalCell.getZ() * 0.5 + 0.25);

        return Optional.of(new NeighborFrameTarget(
                sourceFrame,
                destinationFrame,
                destinationAssembly.id(),
                MechanismSubLevelService.toPlotPosition(destinationSubLevel, destinationMini),
                physicalSoundPosition,
                stateRotation));
    }

    private static boolean isOwnedServerTarget(
            ServerLevel level,
            ServerSubLevel subLevel,
            BlockPos target) {
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssembly(ownerId)
                .orElse(null);
        if (assembly == null) {
            return false;
        }

        BlockPos miniTarget = target.subtract(subLevel.getPlot().getCenterBlock());
        return MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniTarget);
    }

    static boolean sameLogicalFrameVolume(BlockPos firstMini, BlockPos secondMini) {
        int cells = MiniCoordinateMapper.CELLS_PER_FRAME_AXIS;
        return Math.floorDiv(firstMini.getX(), cells) == Math.floorDiv(secondMini.getX(), cells)
                && Math.floorDiv(firstMini.getY(), cells) == Math.floorDiv(secondMini.getY(), cells)
                && Math.floorDiv(firstMini.getZ(), cells) == Math.floorDiv(secondMini.getZ(), cells);
    }

    private static boolean isNeighboringFrame(BlockPos first, BlockPos second) {
        if (first.equals(second)) {
            return false;
        }
        BlockPos delta = second.subtract(first);
        return Math.abs(delta.getX()) <= 1
                && Math.abs(delta.getY()) <= 1
                && Math.abs(delta.getZ()) <= 1;
    }

    public record NeighborFrameTarget(
            BlockPos sourceFrame,
            BlockPos destinationFrame,
            UUID destinationAssemblyId,
            BlockPos destinationGlobalPosition,
            Vec3 physicalSoundPosition,
            Rotation stateRotation) {
    }
}
