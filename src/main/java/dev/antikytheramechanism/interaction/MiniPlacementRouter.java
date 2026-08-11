package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Routes only parent-world BlockItem placements whose vanilla target is a Mechanism Frame.
 * Direct interaction with already-existing mini blocks remains entirely owned by Sable.
 */
public final class MiniPlacementRouter {
    private static final double HIT_EPSILON = 1.0E-6;
    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MiniPlacementRouter() {
    }

    /**
     * @return null when normal ItemStack/BlockItem handling should continue untouched.
     */
    public static @Nullable InteractionResult route(BlockItem blockItem, UseOnContext context) {
        if (BYPASS_DEPTH.get() > 0) {
            return null;
        }

        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        BlockPos framePos;
        Direction frameBoundary;
        boolean clickedFrameDirectly;
        if (clickedState.is(ModRegistries.MECHANISM_FRAME.get())) {
            // Keep normal full-size frame construction available when clicking another frame.
            if (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()) {
                return null;
            }
            framePos = clickedPos;
            frameBoundary = context.getClickedFace();
            clickedFrameDirectly = true;
        } else {
            BlockPos vanillaTarget = clickedPos.relative(context.getClickedFace());
            if (!level.getBlockState(vanillaTarget).is(ModRegistries.MECHANISM_FRAME.get())) {
                return null;
            }
            if (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()) {
                return null;
            }
            framePos = vanillaTarget;
            // The clicked reference block is outside this face of the frame.
            frameBoundary = context.getClickedFace().getOpposite();
            clickedFrameDirectly = false;
        }

        if (!MiniaturizableRegistry.isAllowed(blockItem.getBlock())) {
            return InteractionResult.FAIL;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        return place(
                (ServerLevel) level,
                framePos,
                frameBoundary,
                context.getClickedFace(),
                context.getClickLocation(),
                clickedFrameDirectly,
                player,
                context.getHand(),
                context.getItemInHand());
    }

    private static InteractionResult place(
            ServerLevel level,
            BlockPos framePos,
            Direction frameBoundary,
            Direction clickedFace,
            Vec3 parentHitLocation,
            boolean clickedFrameDirectly,
            Player player,
            InteractionHand hand,
            ItemStack stack) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        if (manager.isFrameLifecycleLocked(framePos)) {
            return InteractionResult.FAIL;
        }

        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElse(null);
        if (assembly == null) {
            assembly = manager.onFramePlaced(level, framePos);
        }
        ServerSubLevel subLevel = MechanismSubLevelService.getOrCreate(level, assembly);
        if (subLevel == null) {
            return InteractionResult.FAIL;
        }

        CellSelection selection = selectBoundaryCell(framePos, frameBoundary, parentHitLocation);
        BlockPos miniPosition = MiniCoordinateMapper.frameToMini(
                assembly,
                framePos,
                selection.x(),
                selection.y(),
                selection.z());
        if (!MechanismSubLevelService.canAddressMiniPosition(level, subLevel, miniPosition)) {
            return InteractionResult.FAIL;
        }

        BlockPos globalTarget = MechanismSubLevelService.toPlotPosition(subLevel, miniPosition);
        BlockState before = level.getBlockState(globalTarget);
        if (!before.canBeReplaced()) {
            return InteractionResult.FAIL;
        }

        final BlockPos syntheticClickedPos;
        final Direction syntheticClickedFace;
        if (clickedFrameDirectly) {
            // The real frame cage is only an aiming surface. Point BlockItem at the replaceable mini
            // cell itself, exactly as vanilla does when the clicked state can be replaced.
            syntheticClickedPos = globalTarget;
            syntheticClickedFace = clickedFace;
        } else {
            // Preserve the actual placement relationship. Clicking the full-size floor below a frame,
            // for example, becomes clicking the read-only virtual floor immediately below the mini cell.
            syntheticClickedPos = globalTarget.relative(frameBoundary);
            syntheticClickedFace = clickedFace;
        }

        Vec3 localHitLocation = syntheticHitLocation(
                syntheticClickedPos,
                syntheticClickedFace,
                selection);
        BlockHitResult localHit = new BlockHitResult(
                localHitLocation,
                syntheticClickedFace,
                syntheticClickedPos,
                false);

        InteractionResult placementResult;
        SubLevelHelper.pushEntityLocal(subLevel, player);
        int previousBypass = BYPASS_DEPTH.get();
        BYPASS_DEPTH.set(previousBypass + 1);
        try {
            placementResult = stack.useOn(new UseOnContext(player, hand, localHit));
        } finally {
            if (previousBypass == 0) {
                BYPASS_DEPTH.remove();
            } else {
                BYPASS_DEPTH.set(previousBypass);
            }
            SubLevelHelper.popEntityLocal(subLevel, player);
        }

        BlockState after = level.getBlockState(globalTarget);
        boolean placed = placementResult.consumesAction()
                && !after.isAir()
                && !after.equals(before);
        if (placed) {
            manager.refreshFrame(level, framePos);
        }
        return placed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    static CellSelection selectBoundaryCell(BlockPos framePos, Direction frameBoundary, Vec3 hitLocation) {
        double localX = clampUnit(hitLocation.x - framePos.getX());
        double localY = clampUnit(hitLocation.y - framePos.getY());
        double localZ = clampUnit(hitLocation.z - framePos.getZ());

        int x = half(localX);
        int y = half(localY);
        int z = half(localZ);
        switch (frameBoundary) {
            case DOWN -> y = 0;
            case UP -> y = 1;
            case NORTH -> z = 0;
            case SOUTH -> z = 1;
            case WEST -> x = 0;
            case EAST -> x = 1;
        }
        return new CellSelection(
                x,
                y,
                z,
                withinSelectedHalf(localX, x),
                withinSelectedHalf(localY, y),
                withinSelectedHalf(localZ, z));
    }

    private static Vec3 syntheticHitLocation(
            BlockPos clickedPos,
            Direction clickedFace,
            CellSelection selection) {
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

    private static int half(double coordinate) {
        return coordinate >= 0.5 ? 1 : 0;
    }

    private static double withinSelectedHalf(double coordinate, int half) {
        double value = half == 0 ? coordinate * 2.0 : (coordinate - 0.5) * 2.0;
        return Math.max(HIT_EPSILON, Math.min(1.0 - HIT_EPSILON, value));
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0 - HIT_EPSILON, value));
    }

    static record CellSelection(int x, int y, int z, double localX, double localY, double localZ) {
    }
}
