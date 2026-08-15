package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.sablescale.scale.SubLevelScale;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/**
 * Identity and invariant service for free half-scale bodies created from Antikythera mini content.
 *
 * <p>These bodies are deliberately <strong>not</strong> Mechanism Frame children. They have no
 * assembly UUID, no FrameMask, no pose driver, no structural stabilizer mass and no Frame loading
 * ticket. They are ordinary Sable physics bodies whose only Antikythera-owned semantics are their
 * fixed 0.5 scale and the miniaturization allow/deny policy.</p>
 */
public final class DetachedMiniPhysicsSubLevelService {
    private static final String MARKER_TAG = "antikytheramechanism_detached";
    private static final String KIND_TAG = "kind";
    private static final String VERSION_TAG = "version";
    private static final String SCALE_TAG = "scale";
    private static final String KIND = "mini_physics";
    private static final int FORMAT_VERSION = 1;
    private static final String NAME_PREFIX = "antikythera_physics-";
    private static final double SCALE_EPSILON = 1.0E-6;

    private DetachedMiniPhysicsSubLevelService() {
    }

    /** Server identity is the persisted marker; clients use the synchronized display-name prefix. */
    public static boolean isDetached(@Nullable SubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return false;
        }
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            return hasMarker(serverSubLevel);
        }
        String name = subLevel.getName();
        return name != null && name.startsWith(NAME_PREFIX);
    }

    public static boolean isDetachedPosition(Level level, BlockPos position) {
        return isDetached(Sable.HELPER.getContaining(level, position));
    }

    /**
     * Marks a newly assembled Sable body as a free Antikythera mini-physics body.
     * Frame-owned children are rejected rather than silently changing lifecycle semantics.
     */
    public static void markDetached(ServerSubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            throw new IllegalArgumentException("Cannot mark a missing or removed SubLevel as detached");
        }
        if (MechanismSubLevelService.getOwnerAssemblyId(subLevel) != null) {
            throw new IllegalArgumentException(
                    "Refusing to convert a Mechanism Frame child into a detached physics body");
        }

        CompoundTag userData = subLevel.getUserDataTag();
        userData = userData == null ? new CompoundTag() : userData.copy();
        CompoundTag marker = new CompoundTag();
        marker.putString(KIND_TAG, KIND);
        marker.putInt(VERSION_TAG, FORMAT_VERSION);
        marker.putDouble(SCALE_TAG, MiniCoordinateMapper.SUBLEVEL_SCALE);
        userData.put(MARKER_TAG, marker);
        subLevel.setUserDataTag(userData);
        // ServerSubLevel#setName broadcasts the change to current tracking players; clients use this
        // prefix because arbitrary user-data is persistence-only and is not a synchronized identity.
        subLevel.setName(NAME_PREFIX + subLevel.getUniqueId());
        enforceHalfScale(subLevel);
        // assembleBlocks can already have inherited scale 0.5 from the Frame child. In that case
        // SubLevelScale#apply is intentionally a no-op, so refresh bounds explicitly after the block
        // transfer/subtype handoff to keep broadphase and the scale-aware placement query current.
        subLevel.updateBoundingBox();
        subLevel.updateLastPose();

        AntikytheraMechanism.LOGGER.debug(
                "Marked Sable SubLevel {} as detached Antikythera half-scale physics body",
                subLevel.getUniqueId());
    }

    public static boolean hasHalfScale(@Nullable SubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return false;
        }
        Vector3dc scale = subLevel.logicalPose().scale();
        return Double.isFinite(scale.x())
                && Double.isFinite(scale.y())
                && Double.isFinite(scale.z())
                && Math.abs(scale.x() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON
                && Math.abs(scale.y() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON
                && Math.abs(scale.z() - MiniCoordinateMapper.SUBLEVEL_SCALE) <= SCALE_EPSILON;
    }

    /** Shared only by policies that intentionally apply to both Frame children and detached bodies. */
    public static boolean usesAntikytheraHalfScalePolicy(@Nullable SubLevel subLevel) {
        return subLevel != null
                && hasHalfScale(subLevel)
                && (MiniWorldEnvironment.isManagedSubLevel(subLevel) || isDetached(subLevel));
    }

    public static void enforceHalfScale(ServerSubLevel subLevel) {
        if (!hasHalfScale(subLevel)) {
            SubLevelScale.apply(subLevel, MiniCoordinateMapper.SUBLEVEL_SCALE);
        }
    }

    private static boolean hasMarker(ServerSubLevel subLevel) {
        CompoundTag userData = subLevel.getUserDataTag();
        if (userData == null || !userData.contains(MARKER_TAG, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag marker = userData.getCompound(MARKER_TAG);
        return KIND.equals(marker.getString(KIND_TAG))
                && marker.getInt(VERSION_TAG) == FORMAT_VERSION
                && (!marker.contains(SCALE_TAG, Tag.TAG_ANY_NUMERIC)
                        || Math.abs(marker.getDouble(SCALE_TAG) - MiniCoordinateMapper.SUBLEVEL_SCALE)
                                <= SCALE_EPSILON);
    }
}
