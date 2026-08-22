package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.ManagedMiniPlacementTargets;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Routes an outward interaction from a mini block on the outer edge of a Frame back into the
 * Frame's physical host. BlockItems use the ordinary placement-stage router, while filled fluid
 * buckets reuse the same face/orientation mapping by rewriting their raycast before BucketItem
 * performs any placement or waterlogging logic.
 */
public final class MicroMacroBoundaryPlacement {
    private static final double HIT_EPSILON = 1.0E-6;
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;

    private MicroMacroBoundaryPlacement() {
    }

    /**
     * @return null when this is not an outward managed-mini placement and the existing mini placement
     *     preflight should keep handling the click.
     */
    public static @Nullable InteractionResult route(
            BlockItem blockItem,
            UseOnContext context,
            BlockPlaceContext placement) {
        Level contextLevel = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        if (!MiniWorldEnvironment.isManagedMiniPosition(contextLevel, clickedPos)) {
            return null;
        }

        // This target is expressed in the child plot's immutable logical axes. Do not reinterpret
        // it as a physical-world direction until the owning assembly has been resolved below.
        BlockPos expectedMiniTarget = clickedPos.relative(context.getClickedFace());
        boolean vanillaChoseOutwardTarget = placement.getClickedPos().equals(expectedMiniTarget);
        boolean forbiddenAsMini = blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()
                || !MiniaturizableRegistry.isAllowed(blockItem.getBlock());
        if (!vanillaChoseOutwardTarget && !forbiddenAsMini) {
            return null;
        }

        // For a forbidden mini block the clicked support may itself be replaceable, causing vanilla
        // BlockPlaceContext to choose the source cell instead of source.relative(face). Geometry is
        // authoritative here: if that adjacent logical cell is outside the FrameMask, this is still
        // an outward macro interaction. Conversely an owned adjacent cell means the click remains
        // inside the Frame and must go back to the ordinary mini rejection/placement path.
        if (ManagedMiniPlacementTargets.isOwnedTarget(contextLevel, clickedPos, expectedMiniTarget)) {
            return null;
        }

        if (contextLevel.isClientSide) {
            // Ownership of the adjacent logical cell is sufficient for prediction to distinguish a
            // real outer face. The server still owns the precise FrameGraph/host mapping and performs
            // the authoritative macro placement below.
            return InteractionResult.SUCCESS;
        }
        if (!(contextLevel instanceof ServerLevel level)) {
            return null;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, clickedPos);
        if (!(containing instanceof ServerSubLevel subLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return InteractionResult.FAIL;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        if (assembly == null || manager.isFrameLifecycleLocked(assembly.origin())) {
            return InteractionResult.FAIL;
        }

        BlockPos miniSource = clickedPos.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniSource)) {
            return InteractionResult.FAIL;
        }

        Direction logicalFace = context.getClickedFace();
        BlockPos cell = MiniCoordinateMapper.cellInFrame(miniSource);
        if (!cellTouchesFace(cell, logicalFace)) {
            return null;
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniSource);
        Direction physicalFace = assembly.orientation().toPhysical(logicalFace);
        if (physicalFace == null
                || !level.hasChunkAt(framePosition)
                || !level.getChunkAt(framePosition).getBlockState(framePosition)
                        .is(ModRegistries.MECHANISM_FRAME.get())
                || manager.getAssemblyAt(framePosition)
                        .map(frameAssembly -> !frameAssembly.id().equals(assembly.id()))
                        .orElse(true)
                || assembly.containsFrame(framePosition.relative(physicalFace))
                || !MechanismAssemblyHost.boundaryIsAligned(
                        level, assembly, HOST_ALIGNMENT_EPSILON)) {
            return InteractionResult.FAIL;
        }

        BlockPos macroTarget = framePosition.relative(physicalFace);
        if (!level.hasChunkAt(macroTarget)) {
            return InteractionResult.FAIL;
        }
        // Another Frame occupies this physical side. It is not an exposed macro face, even when that
        // neighboring Frame belongs to a different assembly; let the caller perform its normal mini
        // rejection instead of trying to place a full-size block through the neighboring cage.
        if (level.getChunkAt(macroTarget).getBlockState(macroTarget)
                .is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }
        if (!MechanismAssemblyHost.samePhysicalHost(level, assembly, macroTarget)) {
            return InteractionResult.FAIL;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }

        Vec3 macroHitLocation = macroHitLocation(
                assembly,
                framePosition,
                clickedPos,
                miniSource,
                physicalFace,
                context.getClickLocation());
        BlockHitResult macroHit = new BlockHitResult(
                macroHitLocation, physicalFace, framePosition, false);

        ItemStack stack = context.getItemInHand();
        InteractionResult result = stack.useOn(new UseOnContext(player, context.getHand(), macroHit));
        return result.consumesAction() ? result : InteractionResult.FAIL;
    }

    /**
     * Rewrites the ray hit used by a filled BucketItem when a mini block is clicked through an
     * outward Frame face. Returning a hit on the physical Frame means vanilla/NeoForge subsequently
     * computes the adjacent macro position itself, so normal fluid placement, macro waterlogging,
     * bucket consumption, sounds and extra bucket content all stay on the vanilla path.
     *
     * <p>{@code null} means the click is not an outward Frame-boundary interaction and the bucket
     * should keep its original hit. An outward click that cannot currently be routed (for example a
     * lifecycle-locked or undocked assembly) is converted to a miss so it cannot fall back to placing
     * the fluid inside the child. On the client we likewise use a miss for the outward prediction;
     * the server owns the authoritative FrameGraph and resolves the physical hit independently.</p>
     */
    public static @Nullable BlockHitResult routeBucketHit(Level level, BlockHitResult miniHit) {
        BlockPos miniGlobal = miniHit.getBlockPos();
        if (!MiniWorldEnvironment.isManagedMiniPosition(level, miniGlobal)) {
            return null;
        }

        Direction logicalFace = miniHit.getDirection();
        BlockPos expectedMiniTarget = miniGlobal.relative(logicalFace);
        if (ManagedMiniPlacementTargets.isOwnedTarget(level, miniGlobal, expectedMiniTarget)) {
            return null;
        }

        if (level.isClientSide) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        SubLevel containing = Sable.HELPER.getContaining(serverLevel, miniGlobal);
        if (!(containing instanceof ServerSubLevel subLevel)
                || !MiniWorldEnvironment.isManagedSubLevel(subLevel)) {
            return null;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (ownerId == null) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(serverLevel);
        MechanismAssembly assembly = manager.getAssembly(ownerId).orElse(null);
        if (assembly == null || manager.isFrameLifecycleLocked(assembly.origin())) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        BlockPos miniSource = miniGlobal.subtract(subLevel.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniSource)) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        BlockPos cell = MiniCoordinateMapper.cellInFrame(miniSource);
        if (!cellTouchesFace(cell, logicalFace)) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniSource);
        Direction physicalFace = assembly.orientation().toPhysical(logicalFace);
        if (physicalFace == null
                || !serverLevel.hasChunkAt(framePosition)
                || !serverLevel.getChunkAt(framePosition).getBlockState(framePosition)
                        .is(ModRegistries.MECHANISM_FRAME.get())
                || manager.getAssemblyAt(framePosition)
                        .map(frameAssembly -> !frameAssembly.id().equals(assembly.id()))
                        .orElse(true)
                || assembly.containsFrame(framePosition.relative(physicalFace))
                || !MechanismAssemblyHost.boundaryIsAligned(
                        serverLevel, assembly, HOST_ALIGNMENT_EPSILON)) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        BlockPos macroTarget = framePosition.relative(physicalFace);
        if (!MechanismAssemblyHost.samePhysicalHost(serverLevel, assembly, macroTarget)) {
            return blockedBucketHit(miniHit, expectedMiniTarget);
        }

        Vec3 macroHitLocation = macroHitLocation(
                assembly,
                framePosition,
                miniGlobal,
                miniSource,
                physicalFace,
                miniHit.getLocation());
        return new BlockHitResult(macroHitLocation, physicalFace, framePosition, false);
    }

    static boolean cellTouchesFace(BlockPos cell, Direction face) {
        return switch (face) {
            case WEST -> cell.getX() == 0;
            case EAST -> cell.getX() == 1;
            case DOWN -> cell.getY() == 0;
            case UP -> cell.getY() == 1;
            case NORTH -> cell.getZ() == 0;
            case SOUTH -> cell.getZ() == 1;
        };
    }

    /** Converts one logical mini hit into the corresponding point on the physical Frame cube. */
    static Vec3 macroHitLocation(
            MechanismAssembly assembly,
            BlockPos framePosition,
            BlockPos miniGlobalPosition,
            BlockPos miniPosition,
            Direction physicalFace,
            Vec3 miniHitLocation) {
        BlockPos cell = MiniCoordinateMapper.cellInFrame(miniPosition);
        double withinX = clampUnit(miniHitLocation.x - miniGlobalPosition.getX());
        double withinY = clampUnit(miniHitLocation.y - miniGlobalPosition.getY());
        double withinZ = clampUnit(miniHitLocation.z - miniGlobalPosition.getZ());

        // First reconstruct the exact hit point in the logical [0,1]^3 Frame cube, then rotate that
        // continuous point with the same orientation basis used for discrete Frame/mini mappings.
        // Rotating only the face direction is insufficient: it leaves the two in-face coordinates
        // mirrored on yawed/rolled Frames and was the cause of opposite-side macro placements.
        double logicalX = (cell.getX() + withinX) * 0.5;
        double logicalY = (cell.getY() + withinY) * 0.5;
        double logicalZ = (cell.getZ() + withinZ) * 0.5;
        Vector3d physical = assembly.orientation().logicalLocalToPhysical(
                logicalX, logicalY, logicalZ, new Vector3d());

        double x = framePosition.getX() + clampUnit(physical.x);
        double y = framePosition.getY() + clampUnit(physical.y);
        double z = framePosition.getZ() + clampUnit(physical.z);
        switch (physicalFace) {
            case WEST -> x = framePosition.getX() + HIT_EPSILON;
            case EAST -> x = framePosition.getX() + 1.0 - HIT_EPSILON;
            case DOWN -> y = framePosition.getY() + HIT_EPSILON;
            case UP -> y = framePosition.getY() + 1.0 - HIT_EPSILON;
            case NORTH -> z = framePosition.getZ() + HIT_EPSILON;
            case SOUTH -> z = framePosition.getZ() + 1.0 - HIT_EPSILON;
        }
        return new Vec3(x, y, z);
    }

    private static BlockHitResult blockedBucketHit(BlockHitResult original, BlockPos expectedMiniTarget) {
        return BlockHitResult.miss(original.getLocation(), original.getDirection(), expectedMiniTarget);
    }

    private static double clampUnit(double value) {
        return Math.max(HIT_EPSILON, Math.min(1.0 - HIT_EPSILON, value));
    }
}
