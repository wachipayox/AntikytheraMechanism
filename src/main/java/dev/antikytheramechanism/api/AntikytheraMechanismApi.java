package dev.antikytheramechanism.api;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.Optional;
import java.util.UUID;

/**
 * Small public integration surface for block compatibility.
 *
 * <p>Registrations are process-wide and may be made during mod construction. Explicit Java denies
 * win over Java allows. The server/common configuration remains a higher-priority user override,
 * except for the mod's immutable hard-deny rules.</p>
 *
 * <p>TODO for a later API version: expose tested, transactional adapter hooks for unusual block
 * entities. No adapter callback is published until its serialization and evacuation contract can
 * be enforced by the runtime.</p>
 */
public final class AntikytheraMechanismApi {
    private AntikytheraMechanismApi() {
    }

    /** Marks one registered block as supported by its owning mod. */
    public static void allow(Block block) {
        MiniaturizableRegistry.registerAllowed(block);
    }

    /** Marks every block in a tag as supported by its owning mod. */
    public static void allow(TagKey<Block> tag) {
        MiniaturizableRegistry.registerAllowed(tag);
    }

    /** Denies one registered block. A Java deny wins over every Java allow. */
    public static void deny(Block block) {
        MiniaturizableRegistry.registerDenied(block);
    }

    /** Denies every block in a tag. A Java deny wins over every Java allow. */
    public static void deny(TagKey<Block> tag) {
        MiniaturizableRegistry.registerDenied(tag);
    }

    /** Evaluates the complete hard-deny, config, Java API and datapack policy. */
    public static MiniaturizationStatus status(Block block) {
        return MiniaturizableRegistry.status(block);
    }

    /** Returns whether the complete policy allows the block. */
    public static boolean isAllowed(Block block) {
        return MiniaturizableRegistry.isAllowed(block);
    }

    /** Returns whether a Sable server sublevel carries this mod's assembly ownership marker. */
    public static boolean isManagedSubLevel(ServerSubLevel subLevel) {
        return subLevel != null
                && !subLevel.isRemoved()
                && MechanismSubLevelService.getOwnerAssemblyId(subLevel) != null;
    }

    /** Returns the persistent assembly id carried by a managed SubLevel. */
    public static Optional<UUID> getAssemblyId(ServerSubLevel subLevel) {
        return Optional.ofNullable(subLevel == null ? null : MechanismSubLevelService.getOwnerAssemblyId(subLevel));
    }

    /** Maps one local mini coordinate back to its owning parent-world frame. */
    public static Optional<BlockPos> miniToFrame(
            ServerLevel parentLevel,
            ServerSubLevel subLevel,
            BlockPos miniPosition) {
        return findAssembly(parentLevel, subLevel)
                .filter(assembly -> MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition))
                .map(assembly -> MiniCoordinateMapper.miniToFrame(assembly, miniPosition));
    }

    /** Maps one of a frame's eight cell coordinates into the managed SubLevel's local space. */
    public static Optional<BlockPos> frameToMini(
            ServerLevel parentLevel,
            ServerSubLevel subLevel,
            BlockPos framePosition,
            int cellX,
            int cellY,
            int cellZ) {
        return findAssembly(parentLevel, subLevel)
                .filter(assembly -> assembly.containsFrame(framePosition))
                .map(assembly -> MiniCoordinateMapper.frameToMini(
                        assembly,
                        framePosition,
                        cellX,
                        cellY,
                        cellZ));
    }

    private static Optional<MechanismAssembly> findAssembly(ServerLevel parentLevel, ServerSubLevel subLevel) {
        if (parentLevel == null || subLevel == null) {
            return Optional.empty();
        }
        UUID assemblyId = MechanismSubLevelService.getOwnerAssemblyId(subLevel);
        if (assemblyId == null) {
            return Optional.empty();
        }
        return MechanismAssemblyManager.get(parentLevel)
                .getAssembly(assemblyId)
                .filter(assembly -> MechanismSubLevelService.findExisting(parentLevel, assembly) == subLevel);
    }
}
