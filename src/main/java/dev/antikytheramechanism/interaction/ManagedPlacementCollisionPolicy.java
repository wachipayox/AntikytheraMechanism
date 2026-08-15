package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Corrects Sable's scale-unaware cross-level block-placement broadphase around Antikythera worlds.
 *
 * <p>Frame children retain the original reservation rule: their parent Frame owns the macro volume,
 * so the Frame/child overlap itself never vetoes mini placement. Detached mini-physics bodies are
 * different: they are free rigid bodies and must still collide with the ground and other bodies.
 * For them this class reproduces Sable's conservative full-cube OBB test using each SubLevel's real
 * Pose3d scale instead of treating every local block as world-size 1x1x1.</p>
 */
public final class ManagedPlacementCollisionPolicy {
    private static final double QUERY_EPSILON = 1.0E-6;
    private static final double SAT_EPSILON_SQUARED = 1.0E-10;

    private ManagedPlacementCollisionPolicy() {
    }

    /** True when BlockItem should replace Sable's injected BlockPlaceContext#canPlace result. */
    public static boolean shouldUseVanillaContextCanPlace(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        SubLevel physicalHost = Sable.HELPER.getContaining(level, target);
        if (DetachedMiniPhysicsSubLevelService.usesAntikytheraHalfScalePolicy(physicalHost)) {
            return true;
        }

        BoundingBox3d targetBounds = worldBounds(target, physicalHost);
        for (SubLevel candidate : Sable.HELPER.getAllIntersecting(level, targetBounds)) {
            if (candidate != physicalHost
                    && DetachedMiniPhysicsSubLevelService.usesAntikytheraHalfScalePolicy(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exact replacement eligibility plus corrected cross-level collision. Frame-only cases preserve
     * their historical reservation bypass; any case involving a detached body uses real Pose3d scale.
     */
    public static boolean correctedContextCanPlace(BlockPlaceContext context) {
        if (!vanillaContextCanPlace(context)) {
            return false;
        }

        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        SubLevel physicalHost = Sable.HELPER.getContaining(level, target);
        BoundingBox3d targetBounds = worldBounds(target, physicalHost);

        boolean detachedInvolved = DetachedMiniPhysicsSubLevelService.isDetached(physicalHost);
        if (!detachedInvolved) {
            for (SubLevel candidate : Sable.HELPER.getAllIntersecting(level, targetBounds)) {
                if (candidate != physicalHost && DetachedMiniPhysicsSubLevelService.isDetached(candidate)) {
                    detachedInvolved = true;
                    break;
                }
            }
        }

        // Existing Frame reservation behavior: when no free body participates, Sable's oversized
        // cross-level OBB is simply irrelevant to this placement.
        if (!detachedInvolved) {
            return true;
        }

        return scaleAwareCrossLevelCanPlace(context, physicalHost, targetBounds);
    }

    private static boolean scaleAwareCrossLevelCanPlace(
            BlockPlaceContext context,
            SubLevel physicalHost,
            BoundingBox3d targetBounds) {
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        LevelReusedVectors sink = new LevelReusedVectors();
        OrientedBoundingBox3d placed = blockObb(target, physicalHost, sink);

        // A Frame-managed target is allowed to overlap its reserved macro Frame volume, exactly as
        // before. A detached target has no such reservation, so actual root blocks remain colliders.
        boolean frameManagedHost = physicalHost != null && MiniWorldEnvironment.isManagedSubLevel(physicalHost);
        if (!frameManagedHost && collidesWithRoot(level, target, physicalHost, targetBounds, placed, sink)) {
            return false;
        }

        for (SubLevel other : Sable.HELPER.getAllIntersecting(level, targetBounds)) {
            if (other == physicalHost || MiniWorldEnvironment.isManagedSubLevel(other)) {
                // Frame children are hosted virtual content, not independent obstacles to placement
                // in a neighbouring free body. Their physical host remains the collision authority.
                continue;
            }
            if (collidesWithSubLevel(level, other, targetBounds, placed, sink)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collidesWithRoot(
            Level level,
            BlockPos target,
            SubLevel physicalHost,
            BoundingBox3d worldBounds,
            OrientedBoundingBox3d placed,
            LevelReusedVectors sink) {
        int minX = Mth.floor(worldBounds.minX()) - 1;
        int minY = Mth.floor(worldBounds.minY()) - 1;
        int minZ = Mth.floor(worldBounds.minZ()) - 1;
        int maxX = Mth.floor(worldBounds.maxX()) + 1;
        int maxY = Mth.floor(worldBounds.maxY()) + 1;
        int maxZ = Mth.floor(worldBounds.maxZ()) + 1;

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            // The local target itself is handled by vanilla replacement eligibility above.
            if (physicalHost == null && pos.equals(target)) {
                continue;
            }
            if (Sable.HELPER.getContaining(level, pos) != null) {
                // Plot-storage coordinates belong to a SubLevel and are handled in its own pass.
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            OrientedBoundingBox3d candidate = new OrientedBoundingBox3d(
                    new Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5),
                    new Vector3d(1.0, 1.0, 1.0),
                    new Quaterniond(),
                    sink);
            if (intersects(candidate, placed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean collidesWithSubLevel(
            Level level,
            SubLevel other,
            BoundingBox3d worldBounds,
            OrientedBoundingBox3d placed,
            LevelReusedVectors sink) {
        BoundingBox3d local = new BoundingBox3d(worldBounds);
        local.transformInverse(other.logicalPose(), local);
        int minX = Mth.floor(local.minX()) - 1;
        int minY = Mth.floor(local.minY()) - 1;
        int minZ = Mth.floor(local.minZ()) - 1;
        int maxX = Mth.floor(local.maxX()) + 1;
        int maxY = Mth.floor(local.maxY()) + 1;
        int maxZ = Mth.floor(local.maxZ()) + 1;

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (Sable.HELPER.getContaining(level, pos) != other) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            if (intersects(blockObb(pos, other, sink), placed)) {
                return true;
            }
        }
        return false;
    }

    private static OrientedBoundingBox3d blockObb(
            BlockPos localPosition,
            SubLevel subLevel,
            LevelReusedVectors sink) {
        Vector3d center = new Vector3d(
                localPosition.getX() + .5,
                localPosition.getY() + .5,
                localPosition.getZ() + .5);
        Vector3d size = new Vector3d(1.0, 1.0, 1.0);
        Quaterniond orientation = new Quaterniond();
        if (subLevel != null) {
            subLevel.logicalPose().transformPosition(center);
            size.set(
                    Math.abs(subLevel.logicalPose().scale().x()),
                    Math.abs(subLevel.logicalPose().scale().y()),
                    Math.abs(subLevel.logicalPose().scale().z()));
            orientation.set(subLevel.logicalPose().orientation());
        }
        return new OrientedBoundingBox3d(center, size, orientation, sink);
    }

    private static boolean intersects(OrientedBoundingBox3d first, OrientedBoundingBox3d second) {
        return OrientedBoundingBox3d.sat(first, second).lengthSquared() > SAT_EPSILON_SQUARED;
    }

    private static BoundingBox3d worldBounds(BlockPos target, SubLevel physicalHost) {
        BoundingBox3d bounds = new BoundingBox3d(target).expand(QUERY_EPSILON);
        if (physicalHost != null) {
            bounds.transform(physicalHost.logicalPose(), bounds);
        }
        return bounds;
    }

    /** Exact vanilla BlockPlaceContext#canPlace logic, before Sable injects its cross-level OBB veto. */
    public static boolean vanillaContextCanPlace(BlockPlaceContext context) {
        return context.replacingClickedOnBlock()
                || context.getLevel().getBlockState(context.getClickedPos()).canBeReplaced(context);
    }
}
