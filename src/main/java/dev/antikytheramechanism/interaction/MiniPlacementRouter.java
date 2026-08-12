package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
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
 * Routes BlockItem placement into the miniature world only when the actual ray hit is an inward
 * surface of a Mechanism Frame bar.
 *
 * <p>The player's location is irrelevant: a player standing outside may look through the cage and
 * hit an inward-facing bar surface. Conversely, clicking an exterior bar surface must retain normal
 * vanilla placement in the parent world.</p>
 */
public final class MiniPlacementRouter {
    private static final double HIT_EPSILON = 1.0E-6;
    private static final double FACE_PROBE_EPSILON = 1.0E-4;
    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MiniPlacementRouter() {
    }

    public static boolean isBypassing() {
        return BYPASS_DEPTH.get() > 0;
    }

    /**
     * @return null when normal ItemStack/BlockItem handling should continue untouched.
     */
    public static @Nullable InteractionResult route(BlockItem blockItem, UseOnContext context) {
        if (BYPASS_DEPTH.get() > 0) {
            return null;
        }

        Level level = context.getLevel();
        BlockPos framePos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(framePos);

        // A mini block hit is already represented by Sable as a plot BlockPos. Let ordinary
        // BlockItem placement handle it; only the Frame cage itself needs explicit routing.
        if (!clickedState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return null;
        }

        // Keep normal full-size frame construction available when clicking another frame.
        if (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()) {
            return null;
        }

        // The normal of the hit surface tells us which side of the thin Frame bar was targeted.
        // Probe a tiny distance along that normal: inside the unit Frame volume means the player
        // targeted an inward face; outside means this is a normal exterior vanilla placement.
        if (!isInteriorFacingFrameHit(framePos, context.getClickedFace(), context.getClickLocation())) {
            return null;
        }

        if (!MiniaturizableRegistry.isAllowed(blockItem.getBlock())) {
            return InteractionResult.FAIL;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            // Server owns the Sable plot and will run the exact transformed placement. Returning
            // success prevents a second full-size placement prediction in the parent world.
            return InteractionResult.SUCCESS;
        }

        return place(
                (ServerLevel) level,
                framePos,
                context.getClickedFace(),
                context.getClickLocation(),
                player,
                context.getHand(),
                context.getItemInHand());
    }

    private static InteractionResult place(
            ServerLevel level,
            BlockPos framePos,
            Direction clickedFace,
            Vec3 parentHitLocation,
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

        CellSelection selection = selectDirectCell(framePos, parentHitLocation);
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
        BlockState before = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        if (!before.canBeReplaced()) {
            return InteractionResult.FAIL;
        }

        // Make the selected replaceable mini cell the clicked position. The Frame itself is semantic
        // support only; MiniWorldEnvironment supplies real outside neighbours where appropriate.
        Vec3 localHitLocation = syntheticHitLocation(globalTarget, clickedFace, selection);
        BlockHitResult localHit = new BlockHitResult(localHitLocation, clickedFace, globalTarget, false);

        InteractionResult placementResult;
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
        }

        BlockState after = level.getChunkAt(globalTarget).getBlockState(globalTarget);
        boolean placed = placementResult.consumesAction()
                && !after.isAir()
                && !after.equals(before);
        if (placed) {
            manager.refreshFrame(level, framePos);
        }
        return placed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    /** Direct frame hits select the actual octant under the cursor, independent of bar face normal. */
    static CellSelection selectDirectCell(BlockPos framePos, Vec3 hitLocation) {
        double localX = clampUnit(hitLocation.x - framePos.getX());
        double localY = clampUnit(hitLocation.y - framePos.getY());
        double localZ = clampUnit(hitLocation.z - framePos.getZ());
        int x = half(localX);
        int y = half(localY);
        int z = half(localZ);
        return new CellSelection(
                x,
                y,
                z,
                withinSelectedHalf(localX, x),
                withinSelectedHalf(localY, y),
                withinSelectedHalf(localZ, z));
    }

    /**
     * True when moving a tiny distance along the clicked surface normal enters the Frame's unit
     * volume. This distinguishes an inner bar face from an exterior bar face without depending on
     * where the player is standing.
     */
    static boolean isInteriorFacingFrameHit(BlockPos framePos, Direction face, Vec3 hitLocation) {
        double probe;
        double min;
        switch (face.getAxis()) {
            case X -> {
                probe = hitLocation.x + face.getStepX() * FACE_PROBE_EPSILON;
                min = framePos.getX();
            }
            case Y -> {
                probe = hitLocation.y + face.getStepY() * FACE_PROBE_EPSILON;
                min = framePos.getY();
            }
            case Z -> {
                probe = hitLocation.z + face.getStepZ() * FACE_PROBE_EPSILON;
                min = framePos.getZ();
            }
            default -> throw new IllegalStateException("Unexpected direction axis " + face.getAxis());
        }
        return probe > min + HIT_EPSILON && probe < min + 1.0 - HIT_EPSILON;
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
