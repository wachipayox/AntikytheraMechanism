package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
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

/**
 * Routes BlockItem placement into the miniature world from either an inward Frame surface or a
 * normal parent-world block whose vanilla placement target is the adjacent Mechanism Frame.
 *
 * <p>The player's location is irrelevant. Clicking an exterior Frame bar surface remains ordinary
 * vanilla placement, while clicking a real floor/wall next to a Frame can use that real block as
 * semantic support for the boundary mini cell.</p>
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
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        // Keep normal full-size frame construction available everywhere.
        if (blockItem.getBlock() == ModRegistries.MECHANISM_FRAME.get()) {
            return null;
        }

        BlockPos framePos;
        CellSelection selection;
        boolean parentSupport;

        if (clickedState.is(ModRegistries.MECHANISM_FRAME.get())) {
            // The normal of the hit surface tells us which side of the thin Frame bar was targeted.
            // Probe a tiny distance along that normal: inside the unit Frame volume means the player
            // targeted an inward face; outside means normal exterior vanilla placement.
            if (!isInteriorFacingFrameHit(clickedPos, context.getClickedFace(), context.getClickLocation())) {
                return null;
            }
            framePos = clickedPos;
            selection = selectDirectCell(framePos, context.getClickLocation());
            parentSupport = false;
        } else {
            // A hit on an actual mini block is already represented by Sable in plot coordinates and
            // must continue through ordinary BlockItem placement. This branch is only for a real
            // parent-world support block immediately outside a Frame.
            if (Sable.HELPER.getContaining(level, clickedPos) != null) {
                return null;
            }

            BlockPlaceContext vanillaContext = new BlockPlaceContext(context);
            BlockPos vanillaTarget = vanillaContext.getClickedPos();
            if (vanillaTarget.equals(clickedPos)
                    || !level.getBlockState(vanillaTarget).is(ModRegistries.MECHANISM_FRAME.get())) {
                return null;
            }

            framePos = vanillaTarget;
            selection = selectBoundaryCell(framePos, context.getClickedFace(), context.getClickLocation());
            parentSupport = true;
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
                selection,
                parentSupport,
                player,
                context.getHand(),
                context.getItemInHand());
    }

    private static InteractionResult place(
            ServerLevel level,
            BlockPos framePos,
            Direction clickedFace,
            CellSelection selection,
            boolean parentSupport,
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

        /*
         * Direct Frame hits intentionally target the replaceable mini cell itself. A placement that
         * originated from a real parent floor/wall is different: vanilla StandingAndWallBlockItem
         * (torches, redstone torches, etc.) relies on BlockPlaceContext seeing the clicked support as
         * non-replaceable so getNearestLookingDirections() prioritizes that exact clicked face.
         *
         * Represent the real support by its read-only virtual shell cell and keep virtual reads active
         * while BlockPlaceContext is constructed. The placement target is still globalTarget, but
         * vanilla now receives the same semantic "clicked solid support face" it would in the parent
         * world instead of an artificial click on replaceable air.
         */
        BlockPos syntheticClickedPos = parentSupport
                ? virtualSupportPosition(globalTarget, clickedFace)
                : globalTarget;
        Vec3 localHitLocation = syntheticHitLocation(syntheticClickedPos, clickedFace, selection);
        BlockHitResult localHit = new BlockHitResult(localHitLocation, clickedFace, syntheticClickedPos, false);

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
     * Selects the boundary mini cell reached by vanilla placement from an adjacent real block.
     * {@code directionIntoFrame} is the clicked face of that support block.
     */
    static CellSelection selectBoundaryCell(
            BlockPos framePos,
            Direction directionIntoFrame,
            Vec3 hitLocation) {
        double localX = clampUnit(hitLocation.x - framePos.getX());
        double localY = clampUnit(hitLocation.y - framePos.getY());
        double localZ = clampUnit(hitLocation.z - framePos.getZ());

        int x = half(localX);
        int y = half(localY);
        int z = half(localZ);
        double cellX = withinSelectedHalf(localX, x);
        double cellY = withinSelectedHalf(localY, y);
        double cellZ = withinSelectedHalf(localZ, z);

        switch (directionIntoFrame.getAxis()) {
            case X -> {
                x = directionIntoFrame.getStepX() > 0 ? 0 : 1;
                cellX = 0.5;
            }
            case Y -> {
                y = directionIntoFrame.getStepY() > 0 ? 0 : 1;
                cellY = 0.5;
            }
            case Z -> {
                z = directionIntoFrame.getStepZ() > 0 ? 0 : 1;
                cellZ = 0.5;
            }
        }

        return new CellSelection(x, y, z, cellX, cellY, cellZ);
    }

    /** Shell cell containing the projected parent support for a boundary placement. */
    static BlockPos virtualSupportPosition(BlockPos target, Direction directionIntoFrame) {
        return target.relative(directionIntoFrame.getOpposite());
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
